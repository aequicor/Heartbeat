package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Model definitions of the bundled Pi's own catalog, read once per profile from an offline probe process that
 * unlocks [PiCatalogProviders] with a placeholder key (no request leaves the machine). A failed probe yields an
 * empty catalog cached for the rest of this profile lifetime, just like a successful empty response. Concurrent
 * callers share one probe; cancellation is propagated and does not cache a partial result.
 */
@SingleIn(ProfileScope::class)
internal class PiBuiltinCatalog(private val probe: suspend (Path, Path) -> List<JsonObject>) {
    @Inject
    constructor(
        dispatchers: DispatcherProvider,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
    ) : this(PiCatalogProbe(dispatchers, profile)::read)

    private val log = Log.tag("PiBuiltinCatalog")
    private val mutex = Mutex()
    private var models: List<JsonObject>? = null

    suspend fun models(executable: Path, runtimeRoot: Path): List<JsonObject> = mutex.withLock {
        models ?: read(executable, runtimeRoot).also { models = it }
    }

    private suspend fun read(executable: Path, runtimeRoot: Path): List<JsonObject> = try {
        probe(executable, runtimeRoot)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "Pi catalog probe failed" }
        emptyList()
    } catch (e: IOException) {
        log.w(e) { "Pi catalog probe could not start" }
        emptyList()
    } catch (e: SecurityException) {
        log.w(e) { "Pi catalog probe was denied" }
        emptyList()
    }
}

/** One isolated offline process; every exit path releases its connection and temporary directory. */
private class PiCatalogProbe(private val dispatchers: DispatcherProvider, private val profile: ScopeHandle) {
    private val log = Log.tag("PiCatalogProbe")

    suspend fun read(executable: Path, runtimeRoot: Path): List<JsonObject> = withContext(dispatchers.io) {
        log.i { "Reading the bundled Pi model catalog" }
        var agentDir: Path? = null
        var connection: PiRpc? = null
        try {
            val directory = Files.createTempDirectory(Files.createDirectories(runtimeRoot), "pi-catalog-")
            agentDir = directory
            Files.writeString(directory.resolve("models.json"), piCatalogProbeJson())
            val builder = ProcessBuilder(probeCommand(executable)).directory(directory.toFile())
            val environment = builder.environment()
            retainPiEnvironment(environment)
            environment["PI_CODING_AGENT_DIR"] = directory.toString()
            environment["PI_OFFLINE"] = "1"
            environment["PI_SKIP_VERSION_CHECK"] = "1"
            // Not cancellable once started: the connection must exist so that `finally` stops the process.
            val rpc = withContext(NonCancellable) {
                val process = builder.start()
                process.onExit().whenComplete { _, _ -> deleteTree(directory) }
                PiRpc(process, profile.coroutineScope, dispatchers, {}, { failure ->
                    log.w(EngineException(failure)) { "Pi catalog probe failed" }
                }).also { connection = it }
            }
            val catalog = (rpc.command("get_available_models")["models"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
            log.i { "Bundled Pi catalog read: ${catalog.size} models" }
            catalog
        } finally {
            // A started process removes its directory on exit; one that never started leaves only models.json.
            connection?.close() ?: agentDir?.let(::deleteTree)
        }
    }

    private fun deleteTree(directory: Path) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(e) { "Pi catalog probe directory cleanup failed" }
        } catch (e: UncheckedIOException) {
            log.w(e) { "Pi catalog probe directory cleanup failed" }
        } catch (e: SecurityException) {
            log.w(e) { "Pi catalog probe directory cleanup was denied" }
        }
    }
}

/** RPC process without sessions, extensions or resources: it only answers catalog commands. */
internal fun probeCommand(executable: Path): List<String> = listOf(
    executable.toString(),
    "--mode",
    "rpc",
    "--no-session",
    "--no-extensions",
    "--no-skills",
    "--no-prompt-templates",
    "--no-context-files",
    "--no-themes",
)
