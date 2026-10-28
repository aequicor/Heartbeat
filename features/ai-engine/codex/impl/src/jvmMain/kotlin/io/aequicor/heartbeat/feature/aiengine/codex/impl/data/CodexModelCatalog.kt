package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One host-owned model catalog for the working process and its offline probe. Model metadata can override
 * feature flags (Code Mode, patching and delegation), so protected sessions use direct hosted functions.
 * Instructions, limits, effort and unknown metadata remain intact; the inventory probe rejects new tools.
 * The file stays alive until the working process exits; closing it more than once is a no-op.
 * Metadata is exported by the selected CLI with the working launch's home, environment and overrides, so
 * account-only models are included. Credentials remain CLI-owned; the offline probe sees only the snapshot.
 * Models absent from that snapshot fail closed, without fallback during verification or execution.
 */
internal class CodexModelCatalog private constructor(
    private val root: Path,
    val path: Path,
    private val models: Set<String>,
) : AutoCloseable {
    fun requireModel(model: String) {
        if (model !in models) inventoryFailure()
    }

    fun requireConfig(config: JsonObject) {
        if (config.text("model_catalog_json") != path.toString()) inventoryFailure()
    }

    private val isClosed = AtomicBoolean(false)

    /** Idempotent: the process-exit callback and a failed launch may both release the same catalog. */
    override fun close() {
        if (isClosed.compareAndSet(false, true)) removeCatalog(root)
    }

    companion object {
        /** Export the same account/configured catalog used by model discovery, then freeze and normalize it. */
        suspend fun create(
            launch: CodexLaunch,
            environment: Map<String, String>,
            dispatchers: DispatcherProvider,
        ): CodexModelCatalog = create(dispatchers) { directory ->
            exportModels(codexModelCatalogCommand(launch), environment, directory)
        }

        /** Export bundled metadata without loading the user's home, credentials or remote model catalog. */
        suspend fun create(executable: String, dispatchers: DispatcherProvider): CodexModelCatalog =
            create(dispatchers) { directory ->
                val home = Files.createDirectory(directory.resolve("home"))
                val environment = System.getenv().filterKeys { it.uppercase() in CodexProbeEnvironment } +
                    ("CODEX_HOME" to home.toString())
                exportModels(listOf(executable, "debug", "models", "--bundled"), environment, directory)
            }

        private suspend fun create(
            dispatchers: DispatcherProvider,
            export: suspend (Path) -> JsonObject,
        ): CodexModelCatalog {
            var root: Path? = null
            var isTransferred = false
            try {
                val result = withContext(dispatchers.io) {
                    val directory = Files.createTempDirectory("heartbeat-codex-models-").also { root = it }
                    val source = export(directory)
                    val normalized = normalizeCodexModels(source)
                    val path = directory.resolve("models.json")
                    Files.writeString(path, normalized.toString())
                    val models = ((normalized["models"] ?: inventoryFailure()) as JsonArray).map {
                        (it as JsonObject).text("slug") ?: inventoryFailure()
                    }.toSet()
                    CodexModelCatalog(directory, path, models)
                }
                isTransferred = true
                return result
            } catch (error: IOException) {
                Log.tag("CodexModelCatalog").w(IllegalStateException(error.javaClass.simpleName)) {
                    "Codex model catalog unavailable"
                }
                inventoryFailure()
            } catch (error: IllegalArgumentException) {
                Log.tag("CodexModelCatalog").w(IllegalStateException(error.javaClass.simpleName)) {
                    "Codex model catalog rejected"
                }
                inventoryFailure()
            } finally {
                if (!isTransferred) withContext(NonCancellable) { cleanup(root, dispatchers) }
            }
        }

        private suspend fun cleanup(root: Path?, dispatchers: DispatcherProvider) = withContext(dispatchers.io) {
            root?.let(::removeCatalog)
        }
    }
}

/** Only native-tool selection is changed; all model-specific prompt and inference metadata is preserved. */
internal fun normalizeCodexModels(source: JsonObject): JsonObject {
    val models = source["models"] as? JsonArray ?: inventoryFailure()
    if (models.isEmpty()) inventoryFailure()
    return JsonObject(
        source + (
            "models" to JsonArray(
                models.map { element ->
                    val model = element as? JsonObject ?: inventoryFailure()
                    if (model.text("slug").isNullOrBlank()) inventoryFailure()
                    buildJsonObject {
                        model.forEach { (key, value) -> put(key, value) }
                        put("tool_mode", "direct")
                        put("multi_agent_version", JsonNull)
                        put("multi_agent_reasoning_effort", JsonNull)
                        put("experimental_supported_tools", JsonArray(emptyList()))
                        put("node_repl_disabled", true)
                        put("shell_type", "disabled")
                        put("apply_patch_tool_type", JsonNull)
                        put("supports_search_tool", false)
                    }
                },
            )
        ),
    )
}

/** File redirection avoids filling a pipe; polling bounds output size, time and cancellation latency. */
private suspend fun exportModels(command: List<String>, environment: Map<String, String>, root: Path): JsonObject {
    val output = root.resolve("exported.json")
    val builder = ProcessBuilder(codexProcessArguments(command))
        .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment().clear()
    builder.environment().putAll(environment)
    val process = builder.start()
    try {
        process.outputStream.close()
        val isComplete = withTimeoutOrNull(CATALOG_TIMEOUT) {
            while (process.isAlive) {
                if (Files.size(output) > MAX_CATALOG_BYTES) inventoryFailure()
                runInterruptible { process.waitFor(CATALOG_POLL_INTERVAL, TimeUnit.MILLISECONDS) }
            }
            true
        }
        if (isComplete != true || process.exitValue() != 0 || Files.size(output) > MAX_CATALOG_BYTES) inventoryFailure()
        return Json.parseToJsonElement(Files.readString(output)) as? JsonObject ?: inventoryFailure()
    } finally {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        if (!process.waitFor(CATALOG_EXIT_SECONDS, TimeUnit.SECONDS)) {
            Log.tag("CodexModelCatalog").w { "Codex model catalog exporter exit timed out" }
        }
    }
}

private fun removeCatalog(root: Path) {
    try {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    } catch (error: IOException) {
        Log.tag("CodexModelCatalog").w(IllegalStateException(error.javaClass.simpleName)) {
            "Codex model catalog cleanup failed"
        }
    } catch (error: UncheckedIOException) {
        // Files.walk reports iteration failures unchecked; the message may contain the temporary path.
        Log.tag("CodexModelCatalog").w(IllegalStateException(error.javaClass.simpleName)) {
            "Codex model catalog cleanup failed"
        }
    }
}

internal val CodexProbeEnvironment =
    setOf("PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL")

private const val CATALOG_TIMEOUT = 15_000L
private const val CATALOG_POLL_INTERVAL = 25L
private const val MAX_CATALOG_BYTES = 16 * 1024 * 1024L

private const val CATALOG_EXIT_SECONDS = 5L
