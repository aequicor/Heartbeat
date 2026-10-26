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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.UncheckedIOException
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
 * Audits the model-facing surface of one protected working thread ([CodexInventoryTarget]) with an isolated local
 * Responses provider. The probe reproduces the working launch's effective feature flags and Off arguments, the
 * host-owned model catalog, the selected model, the thread-level `config` (features, `agents`, `web_search`)
 * and the thread's hosted dynamic declarations (with their real names, descriptions and schemas, or the inert
 * [CODEX_INVENTORY_TOOL] when the thread declares none). Every observed function must be one of those
 * declarations, with the same description and parameter structure, or native `request_user_input`. It is
 * repeated for every protected preparation, so an app update or policy change cannot reuse an earlier approval;
 * the CLI version alone never decides compatibility.
 *
 * Assumptions that cannot be reproduced offline and are therefore accepted, not verified:
 * - The provider and authentication differ: the probe uses a credential-free loopback Responses provider instead
 *   of the user's provider and login. Tool selection is assumed to depend on features, model metadata and thread
 *   config, not on the provider identity or account; provider-specific server-side tools are closed separately
 *   (`web_search=disabled`, search features Off).
 * - MCP servers are absent in the probe home. The working thread disables every configured server by name, so the
 *   probe sends an empty `mcp_servers` table instead of entries that would be incomplete server definitions.
 * - Other user configuration does not enter the probe. Instead, before the probe's thread starts (and so before
 *   any protected working thread), [requireCodexInventoryConfig] refuses an active `profile` and any set top-level
 *   key of the working process's effective config that is neither known to be reproduced or irrelevant to the
 *   tool catalog nor equal to the probe's own clean-home `config/read` value (the executable's default).
 * - Plugin, marketplace and app tables stay in the user's home and are not reproduced: their model-facing
 *   contributions (bundled MCP servers, apps, skills) are gated by `features.plugins`, `apps` and `remote_plugin`,
 *   which every protected launch sets Off and the working config audit requires to be effectively Off.
 * - On resume the declarations are the currently available subset of the thread's stored tool names.
 * Actual execution still validates its effective config and active sessionFlags before this probe.
 */
internal class CodexInventoryProbe(
    private val dispatchers: DispatcherProvider,
    private val catalog: CodexModelCatalog,
) {
    private val log = Log.tag("CodexInventoryProbe")

    suspend fun verify(executable: String, identity: String, target: CodexInventoryTarget, off: CodexNativeOff) {
        var root: Path? = null
        try {
            withContext(dispatchers.io) {
                if (codexExecutableIdentity(executable) != identity) inventoryFailure()
                catalog.requireModel(target.model)
                root = Files.createTempDirectory("heartbeat-codex-inventory-")
            }
            log.d { "Checking Codex native tool isolation" }
            val directory = checkNotNull(root)
            val isAccepted = withTimeoutOrNull(PROBE_TIMEOUT) {
                // Socket accept, process start and teardown block, so the whole capture stays off the UI thread.
                withContext(dispatchers.io) { capture(executable, directory, target, off) }
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
        } catch (error: IllegalStateException) {
            // checkNotNull on a malformed thread/start reply; only the type is kept, as for the other failures.
            log.w(IllegalStateException(error.javaClass.simpleName)) { "Codex inventory probe response incomplete" }
            inventoryFailure()
        } finally {
            withContext(NonCancellable) { removeTemporaryFiles(root) }
        }
    }

    /** Runs from `finally`: IO failures are logged, never thrown, so they cannot mask the probe's own outcome. */
    private suspend fun removeTemporaryFiles(root: Path?) = withContext(dispatchers.io) {
        root?.let { directory ->
            try {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            } catch (error: IOException) {
                log.w(IllegalStateException(error.javaClass.simpleName)) {
                    "Codex inventory temporary files not removed"
                }
            } catch (error: UncheckedIOException) {
                // Files.walk reports iteration failures unchecked; the message may contain the temporary path.
                log.w(IllegalStateException(error.javaClass.simpleName)) {
                    "Codex inventory temporary files not removed"
                }
            }
        }
    }

    private suspend fun awaitExit(running: CodexProbeProcess?) = withContext(dispatchers.io) { running?.awaitExit() }

    private suspend fun capture(executable: String, root: Path, target: CodexInventoryTarget, off: CodexNativeOff) =
        coroutineScope {
            val features = target.effective["features"] as? JsonObject ?: inventoryFailure()
            val tools = target.tools.ifEmpty { listOf(CodexInventoryMarker) }
            CodexInventoryEndpoint().use { endpoint ->
                val received = async { endpoint.receive(dispatchers.io) }
                var running: CodexProbeProcess? = null
                try {
                    running = start(executable, root, features, off, endpoint.baseUrl)
                    val rpc = CodexRpc(checkNotNull(running).wire, this)
                    val events = launch {
                        rpc.notifications.collect { message -> message["id"]?.let { rpc.reject(it) } }
                    }
                    try {
                        rpc.initialize(experimentalApi = true)
                        val configuration = rpc.request("config/read", json("includeLayers" to JsonPrimitive(true)))
                        off.validateConfig(configuration)
                        val baseline = configuration.obj("config")
                        catalog.requireConfig(baseline)
                        requireCodexInventoryConfig(target.effective, baseline)
                        val opened = rpc.request("thread/start", threadParams(root, target, tools))
                        rpc.request(
                            "turn/start",
                            json(
                                "threadId" to checkNotNull(opened.obj("thread")["id"]),
                                "input" to JsonArray(
                                    listOf(json("type" to "text".json(), "text" to "Return no output.".json())),
                                ),
                            ),
                        )
                        validateCodexInventory(received.await(), tools)
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
        args += codexInventoryFeatureArguments(features)
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

    /** Mirrors the working thread/start; only provider, cwd, ephemerality and MCP entries differ. */
    private fun threadParams(root: Path, target: CodexInventoryTarget, tools: List<JsonObject>): JsonObject =
        buildJsonObject {
            put("model", target.model)
            put("modelProvider", "heartbeat_inventory")
            put("cwd", root.resolve("work").toString())
            put("approvalPolicy", "never")
            put("sandbox", "read-only")
            put("ephemeral", true)
            put("dynamicTools", JsonArray(tools))
            put("config", JsonObject(target.thread + ("mcp_servers" to JsonObject(emptyMap()))))
        }

    private companion object {
        const val PROBE_TIMEOUT = 15_000L
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

/**
 * config/read serializes unset optional flags as null (for example network_proxy). Omit them just as an
 * absent key; TOML has no null literal. Explicit booleans remain unchanged. Required Off flags are separately
 * validated in the working process and the probe, so null can never stand in for a mandatory restriction.
 */
internal fun codexInventoryFeatureArguments(features: JsonObject): List<String> = buildList {
    features.forEach { (name, value) ->
        if (value == JsonNull) return@forEach
        if (!InventoryFeatureName.matches(name) || value !in listOf(JsonPrimitive(true), JsonPrimitive(false))) {
            inventoryFailure()
        }
        addAll(listOf("-c", "features.$name=$value"))
    }
}

private val InventoryFeatureName = Regex("[a-zA-Z0-9_]+")
