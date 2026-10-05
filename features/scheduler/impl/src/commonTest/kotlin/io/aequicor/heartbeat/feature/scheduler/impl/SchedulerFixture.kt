package io.aequicor.heartbeat.feature.scheduler.impl

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.data.NetworkStatus
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal val START: Instant = Instant.parse("2026-10-05T10:00:00Z")
internal val SESSION = SessionRef(EngineId("pi"), SessionSourceId("local"), "native-1")
internal val OTHER = SessionRef(EngineId("pi"), SessionSourceId("local"), "native-2")
internal val TARGET = EngineTarget(EngineId("pi"), EngineBindingId("binding"), ModelId("model"))

internal fun wakeRequest(
    id: String,
    events: Set<EventKey> = emptySet(),
    deadline: Instant? = null,
    session: SessionRef = SESSION,
    note: String = "check the build",
) = WakeRequest(
    WakeId(id),
    session,
    null,
    WakeCondition(events, deadline),
    note,
    WakeOrigin.Agent(TurnId("t1")),
    TARGET,
)

internal fun scheduled(id: String, events: Set<EventKey> = emptySet(), deadline: Instant? = null) =
    ScheduledWake(wakeRequest(id, events, deadline), START)

/** Runs the real spec without a runtime; effects are recorded, outputs are emitted. */
internal class SpecMachine(initial: SchedulerState = SchedulerState.Ready()) : SchedulerMachine {
    override val name: String = SchedulerMachineSpec.name
    override val state = MutableStateFlow(initial)
    private val emitted = MutableSharedFlow<SchedulerOutput>(extraBufferCapacity = 16)
    override val outputs: Flow<SchedulerOutput> = emitted
    val effects = mutableListOf<SchedulerEffect>()
    val sent = mutableListOf<SchedulerIntent>()

    override suspend fun send(intent: SchedulerIntent): SendResult {
        sent += intent
        val resolution = SchedulerMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        effects += resolution.effects
        resolution.outputs.forEach { emitted.emit(it) }
        return SendResult.Accepted
    }
}

/** Collects intents an effect sends. */
internal class RecordingScope : EffectScope<SchedulerIntent> {
    val sent = mutableListOf<SchedulerIntent>()

    override suspend fun send(intent: SchedulerIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

/** A host owning [owned] sessions; [failure] makes every wake throw. */
internal class FakeHost(
    override val priority: Int,
    private val owned: Set<SessionRef>? = null,
    var failure: Exception? = null,
) : ScheduledSessionHost {
    val woken = mutableListOf<Pair<WakeRequest, WakePrompt>>()
    val spawned = mutableListOf<SpawnRequest>()
    var spawnResult: SessionRef? = OTHER

    override suspend fun owns(session: SessionRef): Boolean = owned == null || session in owned

    override suspend fun wake(request: WakeRequest, prompt: WakePrompt) {
        failure?.let { throw it }
        woken += request to prompt
    }

    override suspend fun spawn(request: SpawnRequest): SessionRef? {
        spawned += request
        return spawnResult
    }
}

internal class Toggles(enabled: Boolean = true) : FeatureToggles {
    val isEnabled = MutableStateFlow(enabled)

    @Suppress("UNCHECKED_CAST") // The fake answers every flag with one value.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = isEnabled as Flow<T>

    @Suppress("UNCHECKED_CAST") // The fake answers every flag with one value.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled.value as T
}

/** A network whose state the test sets; [observe] follows [state]. */
internal class FakeNetwork(isConnected: Boolean? = null) : NetworkStatus {
    val state = MutableStateFlow(isConnected)

    override fun observe(): Flow<Boolean> = state.filterNotNull()

    override suspend fun current(): Boolean? = state.value
}

/** Wall clock following the virtual time of [scheduler]. */
internal class VirtualClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun now(): Instant = START + scheduler.currentTime.milliseconds
}

internal class MemoryStores : DataStores {
    val store = MemoryStore()
    override val owner: StorageOwner = StorageOwner.App
    override fun filesDirectory(name: String): String = error("unused")
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
    override suspend fun fire(event: DataEvent) = Unit
}

internal class MemoryStore : KeyValueStore {
    override lateinit var spec: KeyValueSpec
    val values = mutableMapOf<String, Any>()

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

    @Suppress("UNCHECKED_CAST") // Test fake: one value type per key.
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = values[key.name] as T?

    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        values[key.name] = value
    }

    override suspend fun remove(key: StoreKey<*>) {
        values.remove(key.name)
    }

    override suspend fun clear() = values.clear()
}
