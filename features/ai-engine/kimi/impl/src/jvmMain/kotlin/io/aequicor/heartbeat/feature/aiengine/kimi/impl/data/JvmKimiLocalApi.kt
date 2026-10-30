package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Clock

/**
 * Read-only telemetry missing from ACP. Each refresh owns a short-lived `kimi web --no-open` process on loopback.
 * Kimi owns provider login; only its local server bearer is read, never OAuth files. Output containing the bearer
 * is discarded. The process and its children are released on success, timeout and cancellation.
 */
@Inject
@SingleIn(ProfileScope::class)
internal class JvmKimiLocalApi(
    private val client: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val setup: KimiRuntimeSetup,
    private val configuration: KimiConfiguration = KimiConfiguration(),
) {
    private val mutex = Mutex()
    private val log = Log.tag("KimiLocalApi")

    suspend fun usage(): ProviderUsageSnapshot? {
        val response = read("/api/v1/oauth/usage") ?: return null
        val usage = kimiLocalProviderUsage(response, Clock.System.now())
        if (usage == null) log.w { "Kimi local API returned no account quota" }
        return usage
    }

    suspend fun modelLimits(): Map<String, Long> {
        log.d { "Reading Kimi model context capacities" }
        return read("/api/v1/models")?.let(::kimiLocalModelLimits).orEmpty()
    }

    private suspend fun read(path: String): JsonObject? = mutex.withLock {
        log.d { "Reading Kimi local telemetry" }
        try {
            val executable = requireNotNull(setup.locate().path)
            val result = withContext(dispatchers.io) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    withKimiWebProcess(dispatchers, { port -> start(executable, port) }) { process, port ->
                        awaitServer(process, port)
                        val token = localToken()
                        readKimiLocalJson(client, port, token, path)
                    }
                }
            }
            if (result == null) log.w { "Kimi local API refresh timed out" }
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // File/process and JSON exceptions may quote paths, credentials or response bodies.
            log.w(KimiLocalDiagnostic(error)) { "Kimi local API refresh failed" }
            null
        }
    }

    private fun start(executable: String, port: Int): Process = ProcessBuilder(
        executable,
        "web",
        "--no-open",
        "--port",
        port.toString(),
    )
        .directory(File(configuration.workingDirectory ?: System.getProperty("user.home")))
        .redirectErrorStream(true)
        .start()

    private fun localToken(): String {
        val home = kimiLocalHome(
            System.getenv("KIMI_CODE_HOME"),
            System.getProperty("user.home"),
            configuration.workingDirectory,
        )
        return File(home, "server.token").inputStream().use { input ->
            val bytes = input.readNBytes(MAX_TOKEN_BYTES + 1)
            require(bytes.size <= MAX_TOKEN_BYTES) { "Invalid Kimi local server bearer" }
            bytes.toString(Charsets.UTF_8).trim().also {
                require(Regex("[A-Za-z0-9_-]{20,256}").matches(it)) { "Invalid Kimi local server bearer" }
            }
        }
    }

    @HighFrequency
    private suspend fun awaitServer(process: Process, port: Int) {
        // The CLI prints its URL only after listening. Never log this buffer: the banner contains its bearer.
        val banner = StringBuilder()
        while (process.isAlive) {
            val count = process.inputStream.available().coerceAtMost(MAX_STARTUP_BYTES)
            if (count > 0) {
                banner.append(process.inputStream.readNBytes(count).toString(Charsets.UTF_8))
                require(banner.length <= MAX_STARTUP_BYTES) { "Kimi local API startup output exceeds limit" }
                if ("http://127.0.0.1:$port/" in banner) return
            }
            delay(START_POLL_MS)
        }
        throw IOException("Kimi local API process exited before readiness")
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val MAX_TOKEN_BYTES = 4096
        const val MAX_STARTUP_BYTES = 32_768
        const val START_POLL_MS = 100L
    }
}

/** Uses the shared, redacting HTTP transport and accepts only the fixed telemetry routes on loopback. */
internal suspend fun readKimiLocalJson(client: HttpClient, port: Int, token: String, path: String): JsonObject {
    require(port in 1..MAX_PORT)
    require(path == "/api/v1/oauth/usage" || path == "/api/v1/models")
    return networkResult {
        client.prepareGet("http://127.0.0.1:$port$path") {
            bearerAuth(token)
            timeout {
                requestTimeoutMillis = LOCAL_HTTP_TIMEOUT_MS
                connectTimeoutMillis = LOCAL_CONNECT_TIMEOUT_MS
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw NetworkException.Http(response.status)
            val text = readKimiLocalBody(response)
            val parsed = Json.parseToJsonElement(text)
            parsed as? JsonObject ?: throw NetworkException.InvalidResponse("JSON object required")
        }
    }.getOrThrow()
}

private suspend fun readKimiLocalBody(response: HttpResponse): String {
    val channel = response.bodyAsChannel()
    val bytes = ByteArray(MAX_RESPONSE_BYTES + 1)
    var size = 0
    while (size < bytes.size) {
        val count = channel.readAvailable(bytes, size, bytes.size - size)
        if (count < 0) break
        size += count
    }
    require(size <= MAX_RESPONSE_BYTES) { "Kimi local API response exceeds limit" }
    return bytes.decodeToString(0, size)
}

/** A relative CLI home follows the child process's working directory, just like Kimi itself. */
internal fun kimiLocalHome(configured: String?, userHome: String, workingDirectory: String?): File {
    val home = configured?.takeIf { it.isNotBlank() }?.let(::File) ?: return File(userHome, ".kimi-code")
    return if (home.isAbsolute) home else File(workingDirectory ?: userHome, home.path)
}

/** Owns just the newly started process; never shuts down an existing user Kimi server. */
internal suspend fun <T> withKimiWebProcess(
    dispatchers: DispatcherProvider,
    start: (Int) -> Process,
    read: suspend (Process, Int) -> T,
): T = withContext(dispatchers.io) {
    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val process = start(port)
    try {
        process.outputStream.close()
        read(process, port)
    } finally {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        process.inputStream.close()
        process.errorStream.close()
    }
}

private class KimiLocalDiagnostic(error: Throwable) :
    Exception("Kimi local API failure: ${error::class.simpleName ?: "unknown"}")

private const val MAX_RESPONSE_BYTES = 1_048_576
private const val MAX_PORT = 65535
private const val LOCAL_HTTP_TIMEOUT_MS = 10_000L
private const val LOCAL_CONNECT_TIMEOUT_MS = 1_000L
