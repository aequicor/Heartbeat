package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.launch

/** Main-confined registry of native UI requests, including jobs waiting to start outside the reader. */
internal class PiNativeApprovals(
    private val environment: PiSessionEnvironment,
    private val hosted: PiHostedSessionTools,
    private val isCurrent: (PiConnection, Turn, Int) -> Boolean,
    private val failed: suspend (EngineFailure) -> Unit,
) {
    private val requests = mutableSetOf<Pair<Int, String>>()

    fun pending(generation: Int): Set<PermissionRequestId> =
        requests.filter { it.first == generation }.mapTo(mutableSetOf()) { PermissionRequestId(it.second) }

    fun launch(id: String, call: PiApprovalCall, turn: Turn, process: PiConnection, generation: Int): Boolean {
        val context = hosted.context() ?: return false
        val key = generation to id
        if (!requests.add(key)) return true
        val approval = PiNativeApproval(
            environment,
            hosted,
            process,
            isCurrent = { isCurrent(process, turn, generation) && context.lifetime?.isActive == true },
            failed = failed,
        )
        environment.profile.coroutineScope.launch(environment.dispatchers.main + requireNotNull(context.lifetime)) {
            approval.answer(id, call, turn, context)
        }.invokeOnCompletion {
            // Also remove jobs cancelled before their first dispatch. Profile closure releases the whole registry.
            environment.profile.coroutineScope.launch(environment.dispatchers.main) { requests.remove(key) }
        }
        return true
    }
}
