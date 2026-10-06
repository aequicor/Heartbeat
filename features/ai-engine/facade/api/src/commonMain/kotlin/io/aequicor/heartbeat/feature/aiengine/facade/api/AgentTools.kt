package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Side-effect category used by the single Heartbeat trust gate. */
public enum class AgentToolAction { Read, Edit, Command }

/** A hosted tool declaration. Schemas contain no credentials or execution identity. */
public data class AgentToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val action: AgentToolAction = AgentToolAction.Read,
)

/** Safe presentation of the action awaiting a user decision; never logged by the dispatcher. */
public data class AgentToolApproval(
    val name: String,
    val title: String,
    val description: String? = null,
    /** Opaque host snapshot binding; handlers validate it atomically before changing durable state. */
    val binding: String? = null,
)

/** Implemented by the native session to publish and answer a hosted permission request. */
public fun interface AgentToolPermissions {
    /** Returns true only after the matching user decision; cancellation propagates. */
    public suspend fun request(approval: AgentToolApproval): Boolean
}

/** Default for sessions without a permission bridge: mutations fail closed. */
public object RejectAgentToolPermissions : AgentToolPermissions {
    override suspend fun request(approval: AgentToolApproval): Boolean = false
}

/**
 * Trusted execution identity supplied by the adapter, never decoded from model arguments.
 * Adapters supply their native turn id; the dispatcher replaces it with the facade id when the request is
 * bound by [ProfileAgentTools.bindTurn]. The permission callback still addresses the native turn.
 * A context belongs to the currently accepted turn and must be revoked when that turn ends.
 */
public data class AgentToolContext(
    val session: SessionRef,
    val workspace: WorkspaceRef?,
    val turn: TurnId,
    val request: RequestId? = null,
    val trust: TrustLevel = TrustLevel.Ask,
    val permissions: AgentToolPermissions = RejectAgentToolPermissions,
    val callId: ToolCallId? = null,
    /** Native turn lifetime; revocation also cancels a bridge call already waiting for authorization. */
    val lifetime: Job? = null,
    /** Authorization snapshot assigned by the dispatcher; models never supply or modify it. */
    val authorization: AgentToolApproval? = null,
    /**
     * Engine and model of the turn. Adapters supply their current target; the dispatcher replaces it with the
     * target recorded by [ProfileAgentTools.bindTurn] for a bound request. Never decoded from model arguments.
     */
    val target: EngineTarget? = null,
    /** Adapter turn identity used by SessionHistory, retained when the dispatcher correlates the facade turn. */
    val historyTurn: TurnId? = null,
) {
    override fun toString(): String = "AgentToolContext"
}

/**
 * What a session's instructions are built for. [workspace] is null for a session without a project; [target] is the
 * session's engine and model when the adapter knows it. [declared] limits instructions to contributions whose tools
 * are among these names — a resumed native thread that froze its tool set at creation; null means no limit.
 */
public data class AgentToolScope(
    val workspace: WorkspaceRef?,
    val target: EngineTarget? = null,
    val declared: Set<String>? = null,
    val session: SessionRef? = null,
    /** Whether the adapter rebuilds declarations at every turn instead of freezing them at session creation. */
    val isRefreshedPerTurn: Boolean = false,
)

/** Bounded tool output returned to the engine; diagnostics must not expose host credentials. */
@Serializable
public data class AgentToolResult(val text: String, val isError: Boolean = false)

/**
 * A profile contribution of hosted tools. Features implement this outside the adapter SPI.
 * Specifications and instructions are scoped to an opaque workspace. IO belongs to the implementation.
 * A session without a project (workspace null) consults only contributions with [isDetachedSupported].
 */
public interface AgentToolContribution {
    /**
     * Adapter-operated host tools publish policy metadata here but execute through their existing transport.
     * They are excluded from generic hosted declarations and execute; adapters must call
     * [ProfileAgentTools.authorizeHosted] immediately before using their own transport. This flag is trusted
     * contribution metadata, never selected by model arguments. Search is an adapter-operated host tool.
     */
    public val isAdapterOperated: Boolean get() = false

    /** Stable settings group key, independent of feature availability. */
    public val group: String get() = "other"

    /** User-facing group title. Contributions sharing a group must use the same title. */
    public val title: String get() = "Другие"

    /** All owned tool names, including disabled ones. Reading it performs no IO. */
    public val catalog: List<ToolCatalogEntry> get() = emptyList()

    /**
     * Whether the tools also serve sessions without a project. Others are never asked about a null workspace,
     * so enabling hosted tools for such sessions does not expose project or desktop tools there. The dispatcher
     * answers any request without a workspace with these; adapters attach them only to sessions opened with
     * [CreateSessionRequest.areDetachedToolsEnabled], which also tells which request decides.
     */
    public val isDetachedSupported: Boolean get() = false

    /** Currently available declarations; duplicate names across contributions are an error. */
    public suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec>

    /** Session-aware declarations; the compatibility default delegates to the workspace form. */
    public suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> = specifications(scope.workspace)

    /** Additional workflow instructions, without execution tokens. */
    public suspend fun instructions(workspace: WorkspaceRef?): String = ""

    /**
     * Instructions for a concrete session; adapters building a system prompt call this form. The default ignores
     * the target. Legacy text is omitted when only part of this contribution is declared, since it may instruct
     * the model to call an unavailable tool. Overrides must honor [AgentToolScope.declared] themselves.
     */
    public suspend fun instructions(scope: AgentToolScope): String =
        if (scope.declared != null && specifications(scope.copy(declared = null)).any {
                it.name !in scope.declared
            }
        ) {
            ""
        } else {
            instructions(scope.workspace)
        }

    /**
     * Requires an explicit user decision beyond the [AgentToolAction] × [TrustLevel] table. It can only add a
     * decision: returning false never skips one the table demands.
     */
    public suspend fun requiresDecision(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): Boolean = false

    /** Presentation for the one trust gate. */
    public fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval =
        AgentToolApproval(spec.name, spec.description)

    /** Captures trusted mutable configuration before the gate; the snapshot is checked again after approval. */
    public suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = approval(spec, arguments)

    /** Executes after authorization; mutable handlers atomically validate [AgentToolContext.authorization]. */
    public suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult

    /**
     * Releases resources owned by this exact turn after the dispatcher revoked and awaited its tool calls.
     * Implementations must bound it in time and log a cleanup they cannot confirm instead of failing the finished
     * turn; the dispatcher only isolates failures, it does not time contributions out.
     */
    public suspend fun finishTurn(session: SessionRef, turn: TurnId): Unit = Unit
}

/** Profile-owned dispatcher shared by native, hosted and MCP adapters. */
// Declaration, invocation and turn cleanup share one profile capability; adapters must use the same instance.
@Suppress("TooManyFunctions")
public interface ProfileAgentTools {
    /**
     * Binds a trusted request to its facade turn before native submission can invoke any hosted tools.
     * [target] is the engine and model the turn runs on; calls of the request see it as [AgentToolContext.target].
     */
    public suspend fun bindTurn(
        session: SessionRef,
        request: RequestId,
        turn: TurnId,
        target: EngineTarget? = null,
    ): Unit = Unit

    /** Available tool declarations for a session's immutable execution workspace. */
    public suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec>

    /** Session-aware declarations. Adapters with a session identity must use this form. */
    public suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> = specifications(scope.workspace)

    /** Static hosted metadata, independent of workspace and toggle availability. */
    public fun catalog(): List<ToolGroup> = emptyList()

    /** Effective native tool policy. Declaration failures fall back to adapter defaults. */
    public suspend fun nativeTools(scope: ToolPolicyScope): ResolvedToolPolicy = ResolvedToolPolicy()

    /** Workflow instructions for the same workspace. */
    public suspend fun instructions(workspace: WorkspaceRef?): String

    /** Instructions for a concrete session: its workspace, engine and model, and the tools it declared. */
    public suspend fun instructions(scope: AgentToolScope): String = instructions(scope.workspace)

    /** Rechecks availability and enforces TrustLevel before invoking a contribution. */
    public suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult

    /**
     * Authorizes an adapter-classified native call under the same turn barrier as hosted calls. Checks policy,
     * then hooks, then adapter trust coverage and permission; rechecks policy after the decision. Cancellation
     * revokes the decision. Adapters must recheck their captured turn before answering the native process.
     */
    public suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict =
        NativeVerdict.Deny("Native authorization is unavailable")

    /**
     * Authorizes a declared [AgentToolContribution.isAdapterOperated] tool, without executing it. Applies the
     * contribution's availability, action, approval, hooks and hosted Off policy under the turn barrier; ordinary
     * hosted tools cannot use this route. Original [arguments] reach the hook and the approval unchanged.
     * The adapter must preserve the captured turn lifetime through its subsequent transport operation.
     */
    public suspend fun authorizeHosted(context: AgentToolContext, name: String, arguments: JsonObject): NativeVerdict =
        NativeVerdict.Deny("Adapter tool authorization is unavailable")

    /**
     * Returns bounded hook context for a completed adapter-operated tool. The adapter calls this only after an
     * authorized operation, under its captured turn lifetime, and prepends the note without replacing the result.
     * Ordinary hosted tools already dispatch afterTool during [execute]. Ended turns return no note.
     */
    public suspend fun afterHosted(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        result: AgentToolResult,
    ): String? = null

    /**
     * Host lifecycle barrier: revoke and await outstanding calls before releasing a turn's resources.
     * [turn] is the facade id registered by [bindTurn], or the native id of an unbound external turn.
     */
    public suspend fun finishTurn(session: SessionRef, turn: TurnId): Unit = Unit
}

/** Optional adapter dependency used when hosted tools are not installed. */
public object NoAgentTools : ProfileAgentTools {
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = emptyList()
    override suspend fun instructions(workspace: WorkspaceRef?): String = ""
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        AgentToolResult("Hosted tools are unavailable", isError = true)
}

/** Loopback bridge address and session capability; neither field may be logged or shown to the model. */
public data class AgentToolBridgeEndpoint(val url: String, val token: String) {
    override fun toString(): String = "AgentToolBridgeEndpoint"
}

/** A session-bound bridge capability. Close revokes access without stopping accepted builds. */
public interface AgentToolBridgeAttachment : AutoCloseable {
    /** Origin exposing /execute and /mcp. */
    public val endpoint: AgentToolBridgeEndpoint

    /** Revokes this attachment; repeated calls are harmless. */
    override fun close()
}

/** Name of the MCP server through which a bridge serves hosted tools; MCP clients prefix tool names with it. */
public const val HOSTED_TOOLS_SERVER: String = "heartbeat_tools"

/** A hosted tool call [name] as an engine reports it, without an MCP client's `mcp__<server>__` prefix. */
public fun hostedToolName(name: String): String = name.removePrefix("mcp__${HOSTED_TOOLS_SERVER}__")

/** Desktop transport for the same dispatcher; each call resolves the current trusted turn context. */
public interface AgentToolBridge {
    /** False on platforms without local processes. */
    public val isAvailable: Boolean

    /**
     * Creates a private capability. A null context rejects calls outside an active turn. A null [workspace] serves
     * a session without a project: only detached contributions answer it.
     */
    public suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment

    /**
     * Creates a capability whose handshake and declarations use this immutable trusted scope. Implementations
     * with scoped policy support also verify the session identity on each call when [AgentToolScope.session] is set.
     */
    public suspend fun attach(
        scope: AgentToolScope,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment = attach(scope.workspace, context)
}

/** Mobile/default bridge rejects local process access. */
public object UnavailableAgentToolBridge : AgentToolBridge {
    override val isAvailable: Boolean = false
    override suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment = throw UnsupportedOperationException("Local agent tools are unavailable")
}
