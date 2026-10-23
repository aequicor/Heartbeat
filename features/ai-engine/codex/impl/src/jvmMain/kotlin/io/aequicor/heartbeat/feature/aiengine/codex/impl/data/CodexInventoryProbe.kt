package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** File identity changes invalidate the probe even when the path and the reported version stay unchanged. */
internal fun codexExecutableIdentity(executable: String): String = try {
    val path = Path.of(executable).toRealPath()
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    "$path:${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime()}"
} catch (error: IOException) {
    Log.tag("CodexInventoryProbe").w(IllegalStateException(error.javaClass.simpleName)) {
        "Codex executable identity unavailable"
    }
    inventoryFailure()
}

/**
 * Audits the selected model and effective feature flags with an isolated local Responses provider. No user
 * config, authentication, prompts, MCP servers or environment overrides enter the probe. It is repeated for
 * every protected preparation, so an app update or policy change cannot reuse a version-based approval.
 * Actual execution still validates its effective config and active sessionFlags before this probe.
 */
internal class CodexInventoryProbe(
    private val dispatchers: DispatcherProvider,
    private val catalog: CodexModelCatalog,
) {
    private val log = Log.tag("CodexInventoryProbe")

    suspend fun verify(
        executable: String,
        identity: String,
        model: String,
        features: JsonObject,
        off: CodexNativeOff,
    ) {
        var root: Path? = null
        try {
            withContext(dispatchers.io) {
                if (codexExecutableIdentity(executable) != identity) inventoryFailure()
                catalog.requireModel(model)
                root = Files.createTempDirectory("heartbeat-codex-inventory-")
            }
            log.d { "Checking Codex native tool isolation" }
            val directory = checkNotNull(root)
            val isAccepted = withTimeoutOrNull(PROBE_TIMEOUT) {
                withContext(dispatchers.main) { capture(executable, directory, model, features, off) }
                withContext(dispatchers.io) {
                    if (codexExecutableIdentity(executable) != identity) inventoryFailure()
                }
                true
            }
            if (isAccepted != true) inventoryFailure()
            log.d { "Codex native tool isolation verified" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "Codex inventory probe IO failed" }
            inventoryFailure()
        } catch (error: IllegalArgumentException) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "Codex inventory probe response rejected" }
            inventoryFailure()
        } finally {
            withContext(NonCancellable) { removeTemporaryFiles(root) }
        }
    }

    private suspend fun removeTemporaryFiles(root: Path?) = withContext(dispatchers.io) {
        root?.let { directory ->
            try {
                Files.walk(
                    directory,
                ).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } catch (error: IOException) {
                log.w(
                    IllegalStateException(error.javaClass.simpleName),
                ) { "Codex inventory temporary files not removed" }
            }
        }
    }

    private suspend fun awaitExit(running: CodexProbeProcess?) = withContext(dispatchers.io) { running?.awaitExit() }

    private suspend fun capture(
        executable: String,
        root: Path,
        model: String,
        features: JsonObject,
        off: CodexNativeOff,
    ) = coroutineScope {
        // Endpoint construction is nonblocking local setup; accept/read run on the injected IO dispatcher.
        CodexInventoryEndpoint().use { endpoint ->
            val received = async { endpoint.receive(dispatchers.io) }
            var running: CodexProbeProcess? = null
            try {
                withContext(dispatchers.io) {
                    running = start(executable, root, features, off, endpoint.baseUrl)
                }
                val rpc = CodexRpc(checkNotNull(running).wire, this)
                val events = launch {
                    rpc.notifications.collect { message -> message["id"]?.let { rpc.reject(it) } }
                }
                try {
                    rpc.initialize(experimentalApi = true)
                    val configuration = rpc.request("config/read", json("includeLayers" to JsonPrimitive(true)))
                    off.validateConfig(configuration)
                    catalog.requireConfig(configuration.obj("config"))
                    val opened = rpc.request("thread/start", threadParams(root, model))
                    rpc.request(
                        "turn/start",
                        json(
                            "threadId" to checkNotNull(opened.obj("thread")["id"]),
                            "input" to JsonArray(
                                listOf(json("type" to "text".json(), "text" to "Return no output.".json())),
                            ),
                        ),
                    )
                    validateCodexInventory(received.await())
                } finally {
                    events.cancel()
                    rpc.close()
                }
            } finally {
                running?.wire?.close()
                received.cancel()
                withContext(NonCancellable) { awaitExit(running) }
            }
        }
    }

    private fun start(
        executable: String,
        root: Path,
        features: JsonObject,
        off: CodexNativeOff,
        url: String,
    ): CodexProbeProcess {
        val home = Files.createDirectory(root.resolve("home"))
        val work = Files.createDirectory(root.resolve("work"))
        val args = mutableListOf(executable, "app-server")
        features.forEach { (name, value) ->
            if (!FEATURE_NAME.matches(
                    name,
                ) || value !in listOf(JsonPrimitive(true), JsonPrimitive(false))
            ) {
                inventoryFailure()
            }
            args += listOf("-c", "features.$name=$value")
        }
        args += off.arguments()
        args += listOf(
            "-c",
            "model_provider=\"heartbeat_inventory\"",
            "-c",
            "model_catalog_json=${JsonPrimitive(catalog.path.toString())}",
            "-c",
            "model_providers.heartbeat_inventory={name=\"Heartbeat inventory\",base_url=\"$url\"," +
                "wire_api=\"responses\",requires_openai_auth=false,supports_websockets=false}",
            "-c",
            "web_search=\"disabled\"",
        )
        val builder = ProcessBuilder(
            codexProcessArguments(args),
        ).directory(work.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
        val retained = builder.environment().filterKeys { it.uppercase() in CodexProbeEnvironment }
        builder.environment().clear()
        builder.environment().putAll(retained)
        builder.environment()["CODEX_HOME"] = home.toString()
        val process = builder.start()
        return CodexProbeProcess(process, ProcessCodexWire(process, dispatchers) {})
    }

    private fun threadParams(root: Path, model: String): JsonObject = buildJsonObject {
        put("model", model)
        put("modelProvider", "heartbeat_inventory")
        put("cwd", root.resolve("work").toString())
        put("approvalPolicy", "never")
        put("sandbox", "read-only")
        put("ephemeral", true)
        put(
            "dynamicTools",
            JsonArray(
                listOf(
                    json(
                        "name" to CODEX_INVENTORY_TOOL.json(),
                        "description" to "Offline inventory marker".json(),
                        "inputSchema" to json("type" to "object".json(), "properties" to json()),
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val PROBE_TIMEOUT = 15_000L
        val FEATURE_NAME = Regex("[a-zA-Z0-9_]+")
    }
}

/** Waits for the killed probe to release its files before temporary-directory removal, including on Windows. */
private class CodexProbeProcess(private val process: Process, val wire: CodexWire) {
    fun awaitExit() {
        if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.tag("CodexInventoryProbe").w { "Codex inventory process exit timed out" }
        }
        process.inputStream.close()
        process.errorStream.close()
    }

    private companion object {
        const val EXIT_TIMEOUT_SECONDS = 5L
    }
}
