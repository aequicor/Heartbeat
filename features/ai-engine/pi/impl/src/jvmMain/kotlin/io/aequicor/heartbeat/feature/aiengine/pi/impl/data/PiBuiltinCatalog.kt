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
 * empty catalog and is retried on the next start: compatible models then simply stay without thinking levels.
 */
@Inject
@SingleIn(ProfileScope::class)
internal class PiBuiltinCatalog(
    private val dispatchers: DispatcherProvider,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("PiBuiltinCatalog")
    private val mutex = Mutex()
    private var models: List<JsonObject>? = null

    suspend fun models(executable: Path, runtimeRoot: Path): List<JsonObject> = mutex.withLock {
        models ?: probe(executable, runtimeRoot).also { if (it.isNotEmpty()) models = it }
    }

    private suspend fun probe(executable: Path, runtimeRoot: Path): List<JsonObject> {
        log.i { "Reading the bundled Pi model catalog" }
        val agentDir = withContext(dispatchers.io) {
            Files.createTempDirectory(Files.createDirectories(runtimeRoot), "pi-catalog-").also {
                Files.writeString(it.resolve("models.json"), piCatalogProbeJson())
            }
        }
        var connection: PiRpc? = null
        return try {
            val builder = ProcessBuilder(probeCommand(executable)).directory(agentDir.toFile())
            val environment = builder.environment()
            environment.keys.retainAll(SAFE_PROBE_ENVIRONMENT)
            environment["PI_CODING_AGENT_DIR"] = agentDir.toString()
            environment["PI_OFFLINE"] = "1"
            environment["PI_SKIP_VERSION_CHECK"] = "1"
            val process = withContext(dispatchers.io) { builder.start() }
            process.onExit().whenComplete { _, _ -> deleteTree(agentDir) }
            val rpc = PiRpc(process, profile.coroutineScope, dispatchers, {}, { failure ->
                log.w(EngineException(failure)) { "Pi catalog probe failed" }
            })
            connection = rpc
            val catalog = (rpc.command("get_available_models")["models"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
            log.i { "Bundled Pi catalog read: ${catalog.size} models" }
            catalog
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Pi catalog probe failed" }
            emptyList()
        } catch (e: IOException) {
            log.w(e) { "Pi catalog probe could not start" }
            emptyList()
        } finally {
            // A started process removes its directory on exit; one that never started leaves only models.json.
            connection?.close() ?: deleteTree(agentDir)
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
        }
    }

    private companion object {
        val SAFE_PROBE_ENVIRONMENT = setOf(
            "PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "COMSPEC", "ComSpec",
            "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "PATHEXT",
        )
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
