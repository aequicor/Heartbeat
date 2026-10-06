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
     * are optional, so an unavailable bridge or a failing contribution leaves a plain chat turn.
     */
    suspend fun prepare(): ClaudeHostedTools? {
        val project = context.workspace
        if (project != null) return attach(project)
        if (!context.areDetachedToolsEnabled) return null
        if (!environment.bridge.isAvailable) {
            log.i { "Hosted tools bridge is unavailable; the chat turn continues without hosted tools" }
            return null
        }
        return try {
            attach(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redacted()) { "Hosted tools failed to attach; the chat turn continues without them" }
            attachment?.close()
            attachment = null
            null
        }
    }

    private suspend fun attach(project: WorkspaceRef?): ClaudeHostedTools? {
        val scope = AgentToolScope(project, context.target, session = context.ref, isRefreshedPerTurn = true)
        val specs = environment.tools.specifications(scope)
        if (specs.isEmpty()) return null
        val declared = scope.copy(declared = specs.mapTo(mutableSetOf()) { it.name })
        val capability = environment.bridge.attach(declared) {
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
        val instructions = environment.tools.instructions(declared)
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
