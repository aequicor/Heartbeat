package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Branch-local live snapshots merge without overwriting another helper's pending requests. Never persisted. */
internal class WorkflowPermissions(private val publish: suspend (Map<SessionRef, List<PermissionRequest>>) -> Unit) {
    private val lock = Mutex()
    private val steps = mutableMapOf<StepKey, Pair<SessionRef, List<PermissionRequest>>>()

    suspend fun update(step: WorkflowStep, permissions: List<PermissionRequest>) = lock.withLock {
        val next = step.session?.takeIf { permissions.isNotEmpty() }?.let { it to permissions.toList() }
        if (steps[step.key] == next) return@withLock
        if (next == null) steps.remove(step.key) else steps[step.key] = next
        publish(
            steps.values.groupBy(
                { it.first },
                { it.second },
            ).mapValues { (_, lists) -> lists.flatten().distinctBy { it.id } },
        )
    }
}
