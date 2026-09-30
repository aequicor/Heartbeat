package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
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
    private val context: suspend () -> AgentToolContext?,
    private val canApprove: (Turn) -> Boolean,
    private val permissions: MutableMap<PermissionRequestId, PermissionRequest>,
    private val send: suspend (ActiveSessionIntent) -> SendResult,
) {
    private val pending = mutableMapOf<PermissionRequestId, CompletableDeferred<Boolean>>()
    private var attachment: AgentToolBridgeAttachment? = null
    var lifetime: CompletableJob? = null
        private set

    fun beginTurn() {
        revoke()
        lifetime = SupervisorJob(environment.profile.coroutineScope.coroutineContext[Job])
    }

    fun revoke() {
        lifetime?.cancel()
        lifetime = null
    }

    suspend fun prepare(workspace: WorkspaceRef?): PiHostedTools? {
        if (workspace == null) return null
        val specs = environment.tools.specifications(workspace)
        if (specs.isEmpty()) return null
        val instructions = environment.tools.instructions(workspace)
        if (!environment.bridge.isAvailable) piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val capability = environment.bridge.attach(workspace, context)
        attachment = capability
        return PiHostedTools(capability.endpoint, specs, instructions)
    }

    suspend fun approval(turn: Turn, action: AgentToolApproval): Boolean {
        val waiting = withContext(environment.dispatchers.main) {
            if (!canApprove(turn)) return@withContext null
            val request = PermissionRequest(
                PermissionRequestId(UUID.randomUUID().toString()),
                turn.id,
                action.title,
                listOf(PermissionOption(Allow, "Разрешить"), PermissionOption(Deny, "Запретить")),
                description = action.description,
            )
            val answer = CompletableDeferred<Boolean>()
            pending[request.id] = answer
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
        answer.complete(decision.option == Allow)
        return true
    }

    fun dismiss() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    fun close() {
        attachment?.close()
        attachment = null
        revoke()
        dismiss()
    }

    private companion object {
        val Allow = PermissionOptionId("hosted.allow")
        val Deny = PermissionOptionId("hosted.deny")
    }
}
