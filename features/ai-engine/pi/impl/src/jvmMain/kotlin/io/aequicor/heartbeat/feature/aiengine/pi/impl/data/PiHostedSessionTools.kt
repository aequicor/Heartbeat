package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.UUID

/** Private transport and hosted approvals of one Pi native session, independent of native extension dialogs. */
internal class PiHostedSessionTools(
    private val environment: PiSessionEnvironment,
    private val isInterrupting: () -> Boolean,
    private val canApprove: (Turn) -> Boolean,
    private val permissions: MutableMap<PermissionRequestId, PermissionRequest>,
    private val send: suspend (ActiveSessionIntent) -> SendResult,
) {
    private val log = Log.tag("PiHostedSessionTools")
    private val pending = mutableMapOf<PermissionRequestId, Pair<CompletableDeferred<Boolean>, PermissionOptionId>>()
    private var attachment: AgentToolBridgeAttachment? = null
    var lifetime: CompletableJob? = null
        private set

    @Volatile
    private var snapshot: AgentToolContext? = null

    /** Immutable authority is captured on HTTP ingress without queuing on Main. */
    fun context(): AgentToolContext? = snapshot?.takeIf {
        it.lifetime?.isActive == true && !isInterrupting()
    }

    fun capture(session: SessionRef, workspace: WorkspaceRef?, turn: Turn, trust: TrustLevel) {
        snapshot = AgentToolContext(
            session,
            workspace,
            turn.id,
            turn.request,
            trust,
            AgentToolPermissions { approval(turn, hostedApproval(turn, it), HostedAllow) },
            lifetime = lifetime,
            target = turn.target,
        )
    }

    fun updateTrust(trust: TrustLevel) {
        snapshot = snapshot?.copy(trust = trust)
    }

    fun beginTurn() {
        revoke()
        lifetime = SupervisorJob(environment.profile.coroutineScope.coroutineContext[Job])
    }

    fun revoke() {
        lifetime?.cancel()
        lifetime = null
        snapshot = null
    }

    /** Rebuilds a process capability with the exact frozen declarations and their scoped instructions. */
    suspend fun prepare(scope: AgentToolScope, specs: List<AgentToolSpec>): PiHostedTools? {
        attachment?.close()
        attachment = null
        if (specs.isEmpty()) return null
        return try {
            val declared = scope.copy(declared = specs.map { it.name }.toSet())
            val instructions = environment.tools.instructions(declared)
            if (!environment.bridge.isAvailable) piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
            val capability = environment.bridge.attach(declared, ::context)
            attachment = capability
            PiHostedTools(capability.endpoint, specs, instructions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (scope.workspace != null) throw e
            log.w(e.withoutDetails()) { "Hosted tools failed to attach; the chat starts without them" }
            null
        }
    }

    /** Waits outside the process reader; native approvals retain their original UI id and option labels. */
    suspend fun approval(turn: Turn, request: PermissionRequest, allow: PermissionOptionId): Boolean {
        val waiting = withContext(environment.dispatchers.main) {
            if (!canApprove(turn) || request.id in pending) return@withContext null
            val answer = CompletableDeferred<Boolean>()
            pending[request.id] = answer to allow
            permissions[request.id] = request
            if (send(ActiveSessionIntent.Internal.PermissionNeeded(request)) != SendResult.Accepted) {
                pending.remove(request.id)
                permissions.remove(request.id)
                return@withContext null
            }
            request to answer
        } ?: return false
        return try {
            waiting.second.await()
        } finally {
            withContext(NonCancellable) { resolve(waiting.first) }
        }
    }

    private suspend fun resolve(request: PermissionRequest) = withContext(environment.dispatchers.main) {
        pending.remove(request.id)
        permissions.remove(request.id)
        send(ActiveSessionIntent.Internal.PermissionResolved(request.turn, request.id))
    }

    fun contains(id: PermissionRequestId): Boolean = id in pending

    fun answer(decision: PermissionDecision): Boolean {
        val answer = pending.remove(decision.request) ?: return false
        answer.first.complete(decision.option == answer.second)
        return true
    }

    fun dismiss() {
        pending.values.forEach { it.first.complete(false) }
        pending.clear()
    }

    fun close() {
        attachment?.close()
        attachment = null
        revoke()
        dismiss()
    }
}

private fun hostedApproval(turn: Turn, action: AgentToolApproval): PermissionRequest = PermissionRequest(
    PermissionRequestId(UUID.randomUUID().toString()),
    turn.id,
    action.title,
    listOf(PermissionOption(HostedAllow, "Разрешить"), PermissionOption(HostedDeny, "Запретить")),
    description = action.description,
)

private val HostedAllow = PermissionOptionId("hosted.allow")
private val HostedDeny = PermissionOptionId("hosted.deny")
