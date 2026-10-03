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
     * Whether the tools also serve sessions without a project. Others are never asked about a null workspace,
     * so enabling hosted tools for such sessions does not expose project or desktop tools there. The dispatcher
     * answers any request without a workspace with these; adapters attach them only to sessions whose creator
     * enabled [CreateSessionRequest.areDetachedToolsEnabled].
     */
    public val isDetachedSupported: Boolean get() = false

    /** Currently available declarations; duplicate names across contributions are an error. */
    public suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec>

    /** Additional workflow instructions, without execution tokens. */
    public suspend fun instructions(workspace: WorkspaceRef?): String = ""

    /**
     * Instructions for a concrete session; adapters building a system prompt call this form. The default ignores
     * the target. The workspace-only form remains for transports announcing the server (MCP `initialize`).
     */
    public suspend fun instructions(scope: AgentToolScope): String = instructions(scope.workspace)

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

    /** Workflow instructions for the same workspace. */
    public suspend fun instructions(workspace: WorkspaceRef?): String

    /** Instructions for a concrete session: its workspace, engine and model, and the tools it declared. */
    public suspend fun instructions(scope: AgentToolScope): String = instructions(scope.workspace)

    /** Rechecks availability and enforces TrustLevel before invoking a contribution. */
    public suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult

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
}

/** Mobile/default bridge rejects local process access. */
public object UnavailableAgentToolBridge : AgentToolBridge {
    override val isAvailable: Boolean = false
    override suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment = throw UnsupportedOperationException("Local agent tools are unavailable")
}
