package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.isAwaited
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Durable result outbox shared by scheduler actions and workflows. Only scheduler actions write running records:
 * workflow execution belongs to its own recovery journal and enters this outbox only after terminal completion.
 * Results survive disabled scheduling and are retired only after matching wakes are durably settled.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class ActionResults(
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val bus: SchedulerBus,
    private val machine: SchedulerMachine,
    private val persistence: SchedulerPersistence,
    private val journal: ActionJournal,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) {
    private val log = Log.tag("ActionResults")
    private val lock = Mutex()
    private val active = mutableSetOf<ActionId>()
    private var recovery: Job? = null

    suspend fun start() = lock.withLock {
        if (recovery != null) return@withLock
        recovery = scope.coroutineScope.launch {
            combine(toggles.observe(SchedulerEnabled), machine.state, persistence.revision) { enabled, state, _ ->
                enabled && state is SchedulerState.Ready
            }.collect { ready -> if (ready) recoverSafely() }
        }
    }

    /** Registers a scheduler-owned operation before its native work starts. */
    suspend fun begin(record: ActionRecord) = lock.withLock {
        check(active.add(record.id)) { "Action already running" }
        var isSaved = false
        try {
            journal.add(record)
            isSaved = true
        } finally {
            if (!isSaved) active.remove(record.id)
        }
    }

    /** A failed startup is forgotten while the profile lives, but remains interrupted after profile shutdown. */
    suspend fun abandon(id: ActionId) = lock.withLock {
        try {
            if (!scope.isClosed) journal.remove(id)
        } finally {
            active.remove(id)
        }
    }

    /** Releases runtime ownership after terminal work or failure; durable records remain available to recovery. */
    suspend fun releaseOwnership(id: ActionId) {
        lock.withLock { active.remove(id) }
    }

    /** Records a terminal workflow result without ever introducing an interruptible scheduler running record. */
    suspend fun finish(action: ActionId, payload: String) {
        start()
        complete(ActionRecord(action, "workflow", clock.now()), payload)
    }

    /** Persists before publishing. A failure propagates, so callers never acknowledge an unsaved result. */
    suspend fun complete(record: ActionRecord, payload: String) {
        val completed = record.copy(payload = payload.take(SchedulerLimits.MAX_PAYLOAD))
        lock.withLock { journal.add(completed) }
        bus.publish(EventKeys.actionFinished(record.id), EventOrigin.Action(record.id), completed.payload)
        recover()
        log.i { "action ${record.id} finished" }
    }

    /** Replays completed results; only scheduler operations not owned by this profile become interrupted. */
    suspend fun recover() {
        if (!toggles.get(SchedulerEnabled)) return
        machine.state.first { it is SchedulerState.Ready }
        lock.withLock {
            journal.readAll().forEach { stored ->
                if (stored.payload == null && stored.id in active) return@forEach
                val record = if (stored.payload != null) {
                    stored
                } else {
                    stored.copy(payload = "status: interrupted (the app closed before the ${stored.kind} finished)")
                        .also { journal.add(it) }
                }
                deliverResult(record)
            }
        }
    }

    private suspend fun deliverResult(record: ActionRecord) {
        val event = BusEvent(
            EventKeys.actionFinished(record.id),
            EventOrigin.Action(record.id),
            clock.now(),
            record.payload,
        )
        val ready = machine.state.value as? SchedulerState.Ready ?: return
        if (ready.wakes.none { it.matches(event) }) {
            if (persistence.revision.value >= ready.revision) journal.remove(record.id)
        } else if (ready.isAwaited(event)) {
            // The bus has no replay: recovery also works before the driver has subscribed.
            machine.send(SchedulerIntent.Internal.Observed(event))
        }
    }

    private suspend fun recoverSafely() {
        try {
            recover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "action recovery failed" }
        }
    }
}
