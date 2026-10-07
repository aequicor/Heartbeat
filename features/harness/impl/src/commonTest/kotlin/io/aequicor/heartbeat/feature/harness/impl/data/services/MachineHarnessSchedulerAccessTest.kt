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
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessLoad
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessProjectSnapshots
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.runtimeRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineHarnessSchedulerAccessTest {
    private val registry = SchedulerAccessRegistry()
    private val toggles = SchedulerAccessToggles()
    private val gate = HarnessEventGate()
    private val access = MachineHarnessSchedulerAccess(registry, toggles, HarnessProjectSnapshots(), gate)
    private val harness = runtimeRequest(1).harness
    private val ready = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)

    @Test
    fun `first collection waits for initial library registration and load`() = runTest {
        val result = async { access.permits(harness.id, null).first() }
        runCurrent()
        assertFalse(result.isCompleted)
        val machine = SchedulerAccessMachine(HarnessState.Loading(HarnessLoad(1, 0)))
        registry.library.value = machine
        gate.open()
        runCurrent()
        assertFalse(result.isCompleted)
        machine.state.value = ready
        runCurrent()
        val permit = assertNotNull(result.await())
        assertTrue(permit.isCurrent())
        machine.state.value = ready.copy(isSuspended = true)
        assertFalse(permit.isCurrent())
    }

    @Test
    fun `startup wait is bounded and off does not resolve registry observations`() = runTest {
        val result = async { access.permits(harness.id, null).first() }
        advanceTimeBy(1001)
        runCurrent()
        assertNull(result.await())
        toggles.enabled.value = false
        registry.observations = 0
        assertNull(access.permits(harness.id, null).first())
        assertEquals(0, registry.observations)
    }

    @Test
    fun `closing an established generation revokes immediately without startup grace`() = runTest {
        registry.library.value = SchedulerAccessMachine(ready)
        gate.open()
        val observed = mutableListOf<HarnessDeliveryPermit?>()
        backgroundScope.launch { access.permits(harness.id, null).collect { observed += it } }
        runCurrent()
        val permit = assertNotNull(observed.single())
        gate.close()
        runCurrent()
        assertNull(observed.last())
        assertFalse(permit.isCurrent())
        gate.open()
        runCurrent()
        assertNotNull(observed.last())
        assertFalse(permit.isCurrent())
    }
}

private class SchedulerAccessMachine(initial: HarnessState) :
    MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput> {
    override val name = "harness"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<HarnessOutput>()
    override suspend fun send(intent: HarnessIntent.Public): SendResult = error("read-only admission")
}

private class SchedulerAccessRegistry : MachineRegistry {
    val library = MutableStateFlow<SchedulerAccessMachine?>(null)
    var observations = 0

    @Suppress("UNCHECKED_CAST") // The fixture publishes only the harness key and an absent worktree registration.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = when (key) {
        HarnessMachineKey -> library.value as MachineRef<S, P, O>?
        WorktreeMachineKey -> null
        else -> error("unexpected key")
    }

    @Suppress("UNCHECKED_CAST") // The two known registrations have the exact state/output types of their keys.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> {
        observations++
        return when (key) {
            HarnessMachineKey -> library as StateFlow<MachineRef<S, P, O>?>
            WorktreeMachineKey -> MutableStateFlow(null)
            else -> error("unexpected key")
        }
    }

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = error("read-only admission")
}

private class SchedulerAccessToggles : FeatureToggles {
    val enabled = MutableStateFlow(true)

    @Suppress("UNCHECKED_CAST") // Admission reads only the boolean harness flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == HarnessEnabled)
        return enabled as Flow<T>
    }
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}
