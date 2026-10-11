package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/** A turn-scoped capability admits handshake without acceptance, and revokes both pending and future calls. */
internal class ClaudeHostedTurn(
    private val context: ClaudeTurnContext,
    private val observer: ClaudeTurnObserver,
    history: ClaudeHistory,
    private val environment: ClaudeSessionEnvironment,
    private val lifetime: Job?,
    private val callbacks: ClaudeTurnCallbacks,
) : AutoCloseable {
    private val log = Log.tag("ClaudeHostedTurn")
    private val isOpen = AtomicBoolean(true)
    private var attachment: AgentToolBridgeAttachment? = null
    val permissions = ClaudePermissions(
        observer,
        history,
        ::isActive,
        callbacks.update,
        callbacks.persist,
        callbacks.canApprove,
    )

    /**
     * Hosted tools for this turn. A project session fails on any error; for a session without a project the tools
     * are optional, so an unavailable bridge or a failing contribution leaves a plain chat turn. Instructions that
     * need no tool (harness content) reach a turn without hosted tools only when the session opted into hooks: the
     * engine declares that it refreshes them, so those hooks leave them to this turn instead of the prompt. Other
     * sessions without hosted tools (judges, research, transfers) get none, as on engines delivering via hooks.
     */
    suspend fun prepare(): ClaudeHostedTools? {
        val project = context.workspace
        if (project != null) return attach(project)
        if (!context.areDetachedToolsEnabled) return optional { instructionsOnly(scope(null)) }
        if (!environment.bridge.isAvailable) {
            log.i { "Hosted tools bridge is unavailable; the chat turn continues without hosted tools" }
            return optional { instructionsOnly(scope(null)) }
        }
        return optional { attach(null) }
    }

    private suspend fun optional(block: suspend () -> ClaudeHostedTools?): ClaudeHostedTools? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redacted()) { "Hosted context failed to prepare; the chat turn continues without it" }
        attachment?.close()
        attachment = null
        null
    }

    private suspend fun attach(project: WorkspaceRef?): ClaudeHostedTools? {
        val scope = scope(project)
        val specs = environment.tools.specifications(scope)
        if (specs.isEmpty()) return instructionsOnly(scope)
        val declared = scope.copy(declared = specs.mapTo(mutableSetOf()) { it.name })
        val capability = environment.bridge.attach(declared) { toolContext() }
        attachment = capability
        val instructions = environment.tools.instructions(declared)
        return ClaudeHostedTools(capability.endpoint, instructions, isProject = project != null)
    }

    /**
     * No tool is declared, so only content independent of tools remains; nothing when it is blank or the session
     * did not opt into the hooks this content stands in for.
     */
    private suspend fun instructionsOnly(scope: AgentToolScope): ClaudeHostedTools? {
        if (!context.areSessionHooksEnabled) return null
        return environment.tools.instructions(scope.copy(declared = emptySet())).takeIf { it.isNotBlank() }?.let {
            ClaudeHostedTools(null, it, isProject = scope.workspace != null)
        }
    }

    private fun scope(project: WorkspaceRef?) =
        AgentToolScope(project, context.target, session = context.ref, isRefreshedPerTurn = true)

    /** Native and hosted calls establish Accepted before publishing a permission, independently of stdout. */
    suspend fun toolContext(): AgentToolContext? {
        if (!isActive()) return null
        observer.acceptHostedCall()
        callbacks.persist()
        if (!isActive()) return null
        return AgentToolContext(
            context.ref,
            context.workspace,
            observer.turn.id,
            context.request.id,
            context.request.trust ?: TrustLevel.Ask,
            permissions,
            lifetime = lifetime,
            target = context.target,
        )
    }

    private fun isActive(): Boolean = isOpen.get() && !observer.isFinished && callbacks.isCurrent()

    override fun close() {
        isOpen.set(false)
        permissions.close()
        attachment?.close()
    }
}

/**
 * Trusted identity and request captured before starting the native process. [target] is the turn's model;
 * [areDetachedToolsEnabled] admits hosted tools without a project for the caller that opted in;
 * [areSessionHooksEnabled] admits instructions independent of tools to a turn without hosted tools.
 */
internal data class ClaudeTurnContext(
    val ref: SessionRef,
    val workspace: WorkspaceRef?,
    val request: PromptRequest,
    val target: EngineTarget? = null,
    val areDetachedToolsEnabled: Boolean = false,
    val areSessionHooksEnabled: Boolean = false,
)

/** Session-owned state updates and serialized persistence; callbacks never replace turn ownership. */
internal data class ClaudeTurnCallbacks(
    val isCurrent: () -> Boolean,
    val update: (ActiveSessionState) -> Unit,
    val persist: suspend () -> Unit,
    val canApprove: () -> Boolean,
)
