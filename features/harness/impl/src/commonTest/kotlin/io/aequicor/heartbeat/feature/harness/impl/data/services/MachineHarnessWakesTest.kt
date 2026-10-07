package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeReceipt
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MachineHarnessWakesTest {
    private val at = Instant.fromEpochSeconds(100)
    private val request = WakeRequest(
        WakeId("wake"),
        SessionRef(EngineId("engine"), SessionSourceId("source"), "native"),
        null,
        WakeCondition(deadline = at),
        "Private note",
        WakeOrigin.Feature("harness"),
        ownerFeature = "harness",
    )

    @Test
    fun `hot subscription exists before synchronous send output and ignores unrelated receipts`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.onSend = {
            assertEquals(1, fixture.machine.outputs.subscriptionCount.value)
            fixture.machine.outputs.emit(SchedulerOutput.Rejected(WakeId("other"), WakeRejection.SessionLimit))
            fixture.machine.outputs.emit(SchedulerOutput.Scheduled(ScheduledWake(request, at)))
            SendResult.Accepted
        }
        assertEquals(HarnessWakeReceipt.Scheduled, fixture.port.schedule(request, at))
        assertEquals(0, fixture.machine.outputs.subscriptionCount.value)
        assertEquals(1, fixture.machine.sent.size)
    }

    @Test
    fun `matching snapshot is positive acknowledgement when the output is lost`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.onSend = {
            fixture.machine.current.value = SchedulerState.Ready(listOf(ScheduledWake(request, at)))
            SendResult.Accepted
        }
        assertEquals(HarnessWakeReceipt.Scheduled, fixture.port.schedule(request, at))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `preexisting identical wake is rejected without dispatching a duplicate`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.current.value = SchedulerState.Ready(listOf(ScheduledWake(request, at)))
        assertEquals(HarnessWakeReceipt.Rejected, fixture.port.schedule(request, at))
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `matching rejection is authoritative without a positive state`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.onSend = {
            fixture.machine.outputs.emit(SchedulerOutput.Rejected(request.id, WakeRejection.SessionLimit))
            SendResult.Accepted
        }
        assertEquals(HarnessWakeReceipt.Rejected, fixture.port.schedule(request, at))
    }

    @Test
    fun `same id with a different immutable request cannot acknowledge this submission`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.onSend = {
            fixture.machine.outputs.emit(SchedulerOutput.Scheduled(ScheduledWake(request.copy(note = "Other"), at)))
            SendResult.Accepted
        }
        assertEquals(HarnessWakeReceipt.Unknown, fixture.port.schedule(request, at))
    }

    @Test
    fun `lost matching receipt stays unknown after bounded wait without resending`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.onSend = {
            val unrelated = ScheduledWake(request.copy(id = WakeId("different")), at)
            fixture.machine.outputs.emit(SchedulerOutput.Scheduled(unrelated))
            SendResult.Accepted
        }
        val result = async { fixture.port.schedule(request, at) }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(HarnessWakeReceipt.Unknown, result.await())
        assertEquals(1, fixture.machine.sent.size)
        assertEquals(0, fixture.machine.outputs.subscriptionCount.value)
    }

    @Test
    fun `caller cancellation closes the receipt subscription`() = runTest {
        val fixture = WakePortFixture()
        val waiting = async { fixture.port.schedule(request, at) }
        runCurrent()
        assertEquals(1, fixture.machine.outputs.subscriptionCount.value)
        waiting.cancelAndJoin()
        assertEquals(0, fixture.machine.outputs.subscriptionCount.value)
        assertEquals(1, fixture.machine.sent.size)
    }

    @Test
    fun `cancel refuses delivering or missing wake and confirms pending removal`() = runTest {
        val fixture = WakePortFixture()
        fixture.machine.current.value = SchedulerState.Ready(
            listOf(ScheduledWake(request, at)),
            delivering = setOf(request.id),
        )
        assertFalse(fixture.port.cancel(request.id))
        assertFalse(fixture.port.cancel(WakeId("missing")))
        assertTrue(fixture.machine.sent.isEmpty())
        fixture.machine.current.value = SchedulerState.Ready(listOf(ScheduledWake(request, at)))
        fixture.machine.onSend = {
            assertEquals(SchedulerIntent.Public.Cancel(request.id), it)
            fixture.machine.current.value = SchedulerState.Ready()
            SendResult.Accepted
        }
        assertTrue(fixture.port.cancel(request.id))
    }

    @Test
    fun `cancel cannot mutate replacement registry with the same wake id`() = runTest {
        val fixture = WakePortFixture()
        val replacement = WakePortMachine()
        val wakes = SchedulerState.Ready(listOf(ScheduledWake(request, at)))
        fixture.machine.current.value = wakes
        replacement.current.value = wakes
        replacement.onSend = {
            replacement.current.value = SchedulerState.Ready()
            SendResult.Accepted
        }
        fixture.machine.beforeRead = { fixture.registry.current.value = replacement }
        assertFalse(fixture.port.cancel(request.id))
        assertTrue(replacement.sent.isEmpty())
        assertEquals(wakes, replacement.current.value)
    }

    @Test
    fun `replacement during schedule cannot acknowledge a different registration`() = runTest {
        val fixture = WakePortFixture()
        val replacement = WakePortMachine()
        fixture.machine.onSend = {
            fixture.registry.current.value = replacement
            fixture.machine.current.value = SchedulerState.Ready(listOf(ScheduledWake(request, at)))
            SendResult.Accepted
        }
        assertEquals(HarnessWakeReceipt.Unknown, fixture.port.schedule(request, at))
        assertTrue(replacement.sent.isEmpty())
        assertEquals(0, fixture.machine.outputs.subscriptionCount.value)
    }
}

private class WakePortFixture {
    val machine = WakePortMachine()
    val registry = WakePortRegistry(machine)
    val port = MachineHarnessWakes(registry, WakePortToggles())
}

private class WakePortMachine : MachineRef<SchedulerState, SchedulerIntent.Public, SchedulerOutput> {
    override val name = SchedulerMachineKey.name
    val current = MutableStateFlow<SchedulerState>(SchedulerState.Ready())
    var beforeRead: () -> Unit = {}
    override val state: StateFlow<SchedulerState> get() {
        beforeRead()
        return current
    }
    override val outputs = MutableSharedFlow<SchedulerOutput>()
    val sent = mutableListOf<SchedulerIntent.Public>()
    var onSend: suspend (SchedulerIntent.Public) -> SendResult = { SendResult.Accepted }
    override suspend fun send(intent: SchedulerIntent.Public): SendResult {
        sent += intent
        return onSend(intent)
    }
}

private class WakePortRegistry(machine: WakePortMachine) : MachineRegistry {
    val current = MutableStateFlow<WakePortMachine?>(machine)

    @Suppress("UNCHECKED_CAST") // This registry fixture exposes only the typed scheduler key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? {
        check(key === SchedulerMachineKey)
        return current.value as MachineRef<S, P, O>?
    }

    @Suppress("UNCHECKED_CAST") // The single mutable registration always contains scheduler machines.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> {
        check(key === SchedulerMachineKey)
        return current as StateFlow<MachineRef<S, P, O>?>
    }

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
}

private class WakePortToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // This adapter reads only the boolean scheduler flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == SchedulerEnabled)
        return flowOf(true as T)
    }
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}
