package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Prepares native thread configuration and validates fresh attachment before a live session is published. */
internal class CodexThreadSetup(private val runtime: CodexRuntime) {
    private val host get() = runtime.host
    private val config get() = host.config
    private val identity get() = runtime.identity
    private val toggles get() = host.toggles
    private val isSearchToolsEnabled get() = runtime.isSearchToolsEnabled
    private val log = Log.tag("CodexThreadSetup")

    suspend fun request(
        nativeId: String?,
        target: EngineTarget,
        route: ExecutionRoute,
        areDetachedToolsEnabled: Boolean,
    ): CodexThreadRequest = CodexThreadRequest(
        nativeId,
        target,
        route,
        areDetachedToolsEnabled,
        policy(ToolPolicyScope(identity.engine, route.workspace, nativeId?.let(::sessionRef), target)),
    )

    suspend fun policy(scope: ToolPolicyScope): ResolvedToolPolicy = try {
        host.tools.nativeToolsForExecution(scope)
            ?: fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        throw e
    } catch (e: Exception) {
        log.w(IllegalStateException("Native policy unavailable (${e::class.simpleName.orEmpty()})")) {
            "Codex execution policy could not be established"
        }
        fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    }

    suspend fun prepare(request: CodexThreadRequest, connection: CodexConnection): CodexPreparedThread {
        val nativeId = request.nativeId
        val target = request.target
        val route = request.route
        val areDetachedToolsEnabled = request.areDetachedToolsEnabled
        val hosted = if (route.workspace == null && areDetachedToolsEnabled) {
            detachedOpening(nativeId, target)
        } else {
            hostedOpening(nativeId, target, route.workspace, isEligible = route.workspace != null)
        }
        val params = connection.rpc.threadParams(request, hosted.parameters)
        return CodexPreparedThread(request, params, hosted)
    }

    suspend fun start(prepared: CodexPreparedThread, connection: CodexConnection): JsonObject {
        val nativeId = prepared.request.nativeId
        val response = connection.rpc.request(
            if (nativeId == null) "thread/start" else "thread/resume",
            prepared.params,
        )
        val thread = validateNativeThread(nativeId, response)
        val manifest = prepared.hosted.manifest
        if (nativeId == null && manifest != null) host.manifests.save(checkNotNull(thread.text("id")), manifest)
        return response
    }

    suspend fun attach(request: CodexThreadRequest, connection: CodexConnection): CodexSession {
        val prepared = prepare(request, connection)
        val response = start(prepared, connection)
        val thread = response.obj("thread")
        val turns = thread["turns"]?.let { it as? JsonArray ?: protocolFailure() }
        val nativeId = request.nativeId
        val session = CodexSession(
            sessionRef(checkNotNull(thread.text("id"))),
            request.route,
            request.target,
            runtime,
            connection,
            prepared.hosted.isServed,
            prepared,
        )
        var isLoaded = false
        try {
            val isUnpaged = listOf("turnsBackwardsCursor", "itemsBackwardsCursor").all { field ->
                val cursor = response[field]
                cursor == null || cursor == JsonNull
            }
            session.load(
                turns,
                isNew = nativeId == null,
                isCanonical = thread.text("historyMode") == "paginated" && isUnpaged,
            )
            isLoaded = true
        } finally {
            if (!isLoaded) session.shutdown(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        return session
    }

    /** Hosted tools of a thread being opened; a thread [isEligible] for them fails to open when they fail. */
    private suspend fun hostedOpening(
        nativeId: String?,
        target: EngineTarget,
        workspace: WorkspaceRef?,
        isEligible: Boolean,
    ): HostedOpening {
        val scope = AgentToolScope(workspace, target, session = nativeId?.let(::sessionRef))
        val specs = if (isEligible) host.tools.specifications(scope) else emptyList()
        val manifest = hostedManifest(workspace, specs)
        val hosted = validateHostedResume(nativeId, workspace, manifest)
        val parameters = hosted.takeIf { isEligible }?.let { hostedParameters(scope, specs, it) }
        return HostedOpening(manifest, parameters, isServed = isEligible && hosted != null)
    }

    /**
     * Detached hosted tools are optional: a failing contribution opens the chat thread as if its caller had not
     * opted in. Engine failures, such as a stored manifest incompatible with a resume, still fail the open.
     */
    private suspend fun detachedOpening(nativeId: String?, target: EngineTarget): HostedOpening = try {
        hostedOpening(nativeId, target, workspace = null, isEligible = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        throw e
    } catch (e: Exception) {
        // Contribution failures may quote instructions or arguments; only the type is logged.
        log.w(IllegalStateException("Detached hosted tools failed (${e::class.simpleName.orEmpty()})")) {
            "Detached hosted tools unavailable; the chat thread opens without them"
        }
        hostedOpening(nativeId, target, workspace = null, isEligible = false)
    }

    /**
     * Hosted declarations of the thread: [HostedThread.New] for a new thread, the stored tool names on resume, or
     * null for a thread without hosted tools. A resumed thread keeps the tools it was created with: a tool whose
     * declaration changed fails the resume, while added tools stay invisible to it and removed ones are refused
     * when called.
     */
    private suspend fun validateHostedResume(
        nativeId: String?,
        workspace: WorkspaceRef?,
        expected: String?,
    ): HostedThread? {
        if (nativeId == null) return HostedThread.New
        val stored = host.manifests.get(nativeId)
        val isRequired = stored != null || host.manifests.isRequired(nativeId)
        if (isRequired && (stored == null || !isManifestCompatible(stored, workspace, expected))) {
            fail(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        return stored?.let { HostedThread.Resumed(manifestTools(it).keys) }
    }

    private fun validateNativeThread(nativeId: String?, response: JsonObject): JsonObject {
        validateNativeIsolation(response)
        val thread = response.obj("thread")
        val id = thread.text("id") ?: protocolFailure()
        if (nativeId != null && nativeId != id) protocolFailure()
        if (thread.text("modelProvider")?.let { it != "openai" } == true) protocolFailure()
        val turns = thread["turns"]?.let { it as? JsonArray ?: protocolFailure() }
        validateIdle(thread, turns.orEmpty())
        return thread
    }

    private fun validateNativeIsolation(response: JsonObject) {
        val sandbox = response["sandbox"] as? JsonObject
        val isPolicyMatching = response.text("approvalPolicy") == APPROVAL_POLICY
        val isSandboxMatching = sandbox?.text("type") == "readOnly" && sandbox["networkAccess"] == JsonPrimitive(false)
        if (!isPolicyMatching || !isSandboxMatching) fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    }

    /** Resume restores native declarations; changing them silently would advertise tools Codex cannot call. */
    private fun hostedManifest(workspace: WorkspaceRef?, tools: List<AgentToolSpec>): String? {
        if (tools.isEmpty()) return null
        return buildJsonObject {
            put("version", MANIFEST_VERSION)
            put("workspace", workspace?.value)
            put(
                "tools",
                JsonArray(
                    tools.sortedBy { it.name }.map { spec ->
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("schema", spec.inputSchema)
                        }
                    },
                ),
            )
        }.toString()
    }

    private fun validateIdle(thread: JsonObject, turns: List<JsonElement>) {
        val isActive = (thread["status"] as? JsonObject)?.text("type") == "active"
        if (isActive || turns.any { (it as? JsonObject)?.text("status") == "inProgress" }) {
            fail(EngineFailure.Session(SessionFailureReason.Busy))
        }
    }

    private suspend fun CodexRpc.threadParams(
        request: CodexThreadRequest,
        hosted: Pair<List<JsonObject>, String>?,
    ): JsonObject {
        val nativeId = request.nativeId
        val target = request.target
        val route = request.route
        val areDetachedToolsEnabled = request.areDetachedToolsEnabled
        val policy = request.policy
        val path = workspacePath(route.workspace)
        val tools = NativeTools(
            isSearchToolsEnabled && toggles.get(SearchEngineTools),
            route.workspace != null || areDetachedToolsEnabled,
        )
        val declarations = hosted?.first.orEmpty() + searchDeclarations(tools.isSearchEnabled, policy)
        val instructions = hosted?.second.orEmpty()
        val isolation = codexIsolationConfig(
            this,
            path,
            CodexIsolationSettings(
                isSearchEnabled = tools.isSearchEnabled,
                areQuestionsEnabled = runtime.questionsEnabled(),
                areSubagentsEnabled = tools.areSubagentsAllowed && toggles.get(
                    io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSubagentsEnabled,
                ),
                off = request.off,
            ),
        )
        return buildJsonObject {
            put("model", target.model.value)
            put("modelProvider", "openai")
            put("approvalPolicy", APPROVAL_POLICY)
            put("sandbox", SANDBOX_MODE)
            if (path != null) put("cwd", path)
            if (nativeId == null && declarations.isNotEmpty()) put("dynamicTools", JsonArray(declarations))
            if ((nativeId != null && hosted != null) || instructions.isNotBlank()) {
                put("developerInstructions", instructions)
            }
            put("config", isolation)
            if (nativeId != null) put("threadId", nativeId)
        }
    }

    private fun searchDeclarations(isEnabled: Boolean, policy: ResolvedToolPolicy): List<JsonElement> {
        if (!isEnabled) return emptyList()
        return searchToolSpecs().filter { (it as JsonObject).text("name") !in policy.hostedDenied }
    }

    private suspend fun workspacePath(workspace: WorkspaceRef?): String? = workspace?.let {
        host.workspaces.resolve(it) ?: config.workspaces[it]
            ?: fail(EngineFailure.Request(RequestFailureReason.Invalid))
    }

    /**
     * Declarations for a new thread and instructions limited to the tools the thread has. The access preamble
     * describes project edits, so a session without a project gets only the contributions' own instructions.
     */
    private suspend fun hostedParameters(
        scope: AgentToolScope,
        specs: List<AgentToolSpec>,
        thread: HostedThread,
    ): Pair<List<JsonObject>, String> {
        val frozen = (thread as? HostedThread.Resumed)?.tools
        val allowed = specs.filter { frozen == null || it.name in frozen }
        val declared = allowed.map { it.name }.toSet()
        val declarations = allowed.map { spec ->
            buildJsonObject {
                put("type", "function")
                put("name", spec.name)
                put("description", spec.description)
                put("inputSchema", spec.inputSchema)
            }
        }
        val instructions = host.tools.instructions(scope.copy(declared = declared))
        val text = if (allowed.isNotEmpty() && scope.workspace != null) {
            codexHostedInstructions(instructions, allowed.map { it.action }.toSet())
        } else {
            instructions
        }
        return declarations to text
    }

    private fun sessionRef(nativeId: String): SessionRef = SessionRef(identity.engine, config.historySource, nativeId)

    private companion object {
        // app-server v2 wire spellings (AskForApproval, SandboxMode), not the Rust variant names.
        const val APPROVAL_POLICY = "never"
        const val SANDBOX_MODE = "read-only"
    }
}

private data class NativeTools(val isSearchEnabled: Boolean, val areSubagentsAllowed: Boolean)
