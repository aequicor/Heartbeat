package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Live appearances of Studio's existing pending permissions, not an execution registry. A process restart loses
 * every issued nonce. Canonical records, the original work receipt and the same native handle must still agree
 * when the facade consumes an appearance under its execution admission barrier.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioPermissionRelays(
    private val records: StudioChatRecords,
    private val attempts: StudioHelperAttempts,
    private val access: Lazy<StudioHelperAccess>,
) {
    private val log = Log.tag("StudioPermissionRelays")
    private val mutex = Mutex()
    private val appearances = mutableMapOf<String, List<Appearance>>()
    private var revision = 0L

    suspend fun update(
        runtime: MutableStateFlow<StudioRuntimeState>,
        id: String,
        state: ActiveSessionState,
        active: ActiveSession?,
        isStopRequested: Boolean,
    ) {
        val projected = project(id, state, active, runtime.value.permissions)
        runtime.update { it.withRelayedPermissionState(id, state, active, isStopRequested, projected) }
    }

    suspend fun project(
        id: String,
        state: ActiveSessionState,
        active: ActiveSession?,
        previous: List<StudioPermission>,
    ): List<StudioPermission> {
        val record = records.record(id)
        if (record.logicalSession?.isHumanInteractive == true) {
            return projectStudioPermissions(id, state, previous)
        }
        val pending = state as? ActiveSessionState.AwaitingUserAction
        val owner = owner(record)
        val isLive = record.isExplicitExecutor && record.executor?.isRetired == false
        val isProjectable = active != null && owner != null && isLive
        if (pending == null || !isProjectable) {
            mutex.withLock { appearances.remove(id) }
            return emptyList()
        }
        return issue(record, pending, active, owner)
    }

    private suspend fun issue(
        record: StudioChatRecord,
        pending: ActiveSessionState.AwaitingUserAction,
        active: ActiveSession,
        owner: LogicalSessionId,
    ): List<StudioPermission> = mutex.withLock {
        val request = pending.turn.request ?: return@withLock emptyList()
        val binding = checkNotNull(record.executorBinding())
        val old = appearances[record.id].orEmpty()
        val current = pending.requests.map { permission ->
            old.singleOrNull {
                it.handle === active && it.relay.binding == binding && it.relay.request == request &&
                    it.relay.target == pending.turn.target && it.relay.permission == permission
            } ?: Appearance(
                active,
                PermissionRelay(
                    binding,
                    owner,
                    active.ref,
                    pending.turn.target,
                    request,
                    permission,
                    Uuid.random().toString(),
                    ++revision,
                ),
            )
        }
        appearances[record.id] = current
        current.mapNotNull { it.project(record.title) }
    }

    private fun Appearance.project(title: String): StudioPermission? {
        val offered = relay.permission.toStudio(relay.owner.value)
        val options = offered.options.filter { it.isSkip || it.isOneTime }
        return if (options.isEmpty()) {
            null
        } else {
            offered.copy(
                requestId = relay.nonce,
                title = "$title: ${offered.title}",
                options = options,
                relay = relay,
            )
        }
    }

    /** Called only by the canonical Studio execution provider under the facade admission mutex. */
    suspend fun enter(relay: PermissionRelay, decision: PermissionDecision): Boolean {
        val child = relay.binding.logicalSession?.id ?: return false
        if (!canonical(relay, child)) return false
        val handle = access.value.liveHelper(HelperId(child.value)) ?: return false
        return mutex.withLock {
            val appearance = appearances[child.value]?.singleOrNull { it.relay == relay } ?: return@withLock false
            val pending = handle.state.value as? ActiveSessionState.AwaitingUserAction
            val isCurrent =
                appearance.handle === handle && !appearance.isConsumed && pending?.turn?.request == relay.request &&
                    pending.turn.target == relay.target && pending.requests.any { it == relay.permission }
            if (!isCurrent || !relay.accepts(decision)) return@withLock false
            appearances[child.value] = appearances.getValue(child.value).map {
                if (it === appearance) it.copy(isConsumed = true) else it
            }
            log.i { "Consume exact owner-mediated Studio permission" }
            true
        }
    }

    private suspend fun canonical(relay: PermissionRelay, child: LogicalSessionId): Boolean {
        val record = records.record(child.value)
        val isLive = record.isExplicitExecutor && record.executor?.isRetired == false
        if (!isLive || record.executorBinding() != relay.binding || owner(record) != relay.owner) return false
        val receipt = attempts.receipt(HelperId(child.value), relay.request) ?: return false
        return receipt.session == relay.session &&
            receipt.phase in setOf(StudioHelperPhase.Submitting, StudioHelperPhase.Accepted) &&
            (receipt.nativeRoute == null || receipt.nativeRoute.target == relay.target)
    }

    private suspend fun owner(record: StudioChatRecord): LogicalSessionId? {
        val owner = record.logicalSession?.owner ?: return null
        val all = resolvedStudioIdentities(records.store.get(ChatsKey).orEmpty())
        return owner.takeIf { candidate ->
            all.singleOrNull { it.id == candidate.value }?.logicalSession?.isHumanInteractive == true
        }
    }

    private data class Appearance(
        val handle: ActiveSession,
        val relay: PermissionRelay,
        val isConsumed: Boolean = false,
    )
}
