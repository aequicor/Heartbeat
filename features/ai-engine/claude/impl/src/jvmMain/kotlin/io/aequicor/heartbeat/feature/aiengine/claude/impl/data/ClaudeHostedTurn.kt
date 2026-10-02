package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
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

    suspend fun prepare(): ClaudeHostedTools? {
        val project = context.workspace
        if (project == null && !context.areDetachedToolsEnabled) return null
        if (environment.tools.specifications(project).isEmpty()) return null
        val capability = environment.bridge.attach(project) {
            if (isActive()) {
                observer.acceptHostedCall()
                callbacks.persist()
                AgentToolContext(
                    context.ref,
                    project,
                    observer.turn.id,
                    context.request.id,
                    context.request.trust ?: TrustLevel.Ask,
                    permissions,
                    lifetime = lifetime,
                    target = context.target,
                )
            } else {
                null
            }
        }
        attachment = capability
        val instructions = environment.tools.instructions(AgentToolScope(project, context.target))
        return ClaudeHostedTools(capability.endpoint, instructions, isProject = project != null)
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
 * [areDetachedToolsEnabled] admits hosted tools without a project for the caller that opted in.
 */
internal data class ClaudeTurnContext(
    val ref: SessionRef,
    val workspace: WorkspaceRef?,
    val request: PromptRequest,
    val target: EngineTarget? = null,
    val areDetachedToolsEnabled: Boolean = false,
)

/** Session-owned state updates and serialized persistence; callbacks never replace turn ownership. */
internal data class ClaudeTurnCallbacks(
    val isCurrent: () -> Boolean,
    val update: (ActiveSessionState) -> Unit,
    val persist: suspend () -> Unit,
    val canApprove: () -> Boolean,
)
