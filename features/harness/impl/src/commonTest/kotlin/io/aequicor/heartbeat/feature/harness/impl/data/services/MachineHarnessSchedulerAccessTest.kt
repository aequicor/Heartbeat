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
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessLoad
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.runtimeRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccess
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class MachineHarnessSchedulerAccessTest {
    private val registry = SchedulerAccessRegistry()
    private val toggles = SchedulerAccessToggles()
    private val gate = HarnessEventGate()
    private val access = MachineHarnessSchedulerAccess(registry, toggles, gate)
    private val harness = runtimeRequest(1).harness
    private val ready = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)

    @Test
    fun `first collection reports unknown until initial library registration and load`() = runTest {
        val observed = mutableListOf<HarnessAccess>()
        backgroundScope.launch { access.permits(harness.id, null).collect { observed += it } }
        runCurrent()
        assertEquals(listOf<HarnessAccess>(HarnessAccess.Unknown), observed)
        val machine = SchedulerAccessMachine(HarnessState.Loading(HarnessLoad(1, 0)))
        registry.library.value = machine
        gate.open()
        runCurrent()
        assertEquals(listOf<HarnessAccess>(HarnessAccess.Unknown), observed)
        machine.state.value = ready
        runCurrent()
        val permit = assertIs<HarnessAccess.Permitted>(observed.last()).permit
        assertTrue(permit.isCurrent())
        machine.state.value = ready.copy(isSuspended = true)
        assertFalse(permit.isCurrent())
    }

    @Test
    fun `startup without known state never becomes a refusal and off resolves no observations`() = runTest {
        val observed = mutableListOf<HarnessAccess>()
        backgroundScope.launch { access.permits(harness.id, null).collect { observed += it } }
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf<HarnessAccess>(HarnessAccess.Unknown), observed)
        assertNull(access.snapshot().allows(harness.id, null))
        toggles.enabled.value = false
        registry.observations = 0
        assertEquals(HarnessAccess.Denied, access.permits(harness.id, null).first())
        assertEquals(0, registry.observations)
    }

    @Test
    fun `unrelated library changes keep a permit current while a new generation revokes it`() = runTest {
        val machine = SchedulerAccessMachine(ready)
        registry.library.value = machine
        gate.open()
        val observed = mutableListOf<HarnessAccess>()
        backgroundScope.launch { access.permits(harness.id, null).collect { observed += it } }
        runCurrent()
        val permit = assertIs<HarnessAccess.Permitted>(observed.single()).permit
        val other = harness.copy(id = HarnessId("other"), name = HarnessName("other"))
        machine.state.value = ready.copy(
            harnesses = ready.harnesses + HarnessEntry(other),
            attachments = mapOf(dispatchSession to setOf(other.id)),
        )
        runCurrent()
        assertTrue(permit.isCurrent())
        assertEquals(1, observed.size)
        assertEquals(true, access.snapshot().allows(harness.id, null))
        gate.close()
        gate.open()
        runCurrent()
        assertFalse(permit.isCurrent())
        assertTrue(assertIs<HarnessAccess.Permitted>(observed.last()).permit.isCurrent())
    }

    @Test
    fun `closing an established generation revokes immediately without startup grace`() = runTest {
        registry.library.value = SchedulerAccessMachine(ready)
        gate.open()
        val observed = mutableListOf<HarnessAccess>()
        backgroundScope.launch { access.permits(harness.id, null).collect { observed += it } }
        runCurrent()
        val permit = assertIs<HarnessAccess.Permitted>(observed.single()).permit
        gate.close()
        runCurrent()
        assertEquals(HarnessAccess.Denied, observed.last())
        assertFalse(permit.isCurrent())
        gate.open()
        runCurrent()
        assertIs<HarnessAccess.Permitted>(observed.last())
        assertFalse(permit.isCurrent())
    }
}

internal class SchedulerAccessMachine(initial: HarnessState) :
    MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput> {
    override val name = "harness"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<HarnessOutput>()
    val sent = mutableListOf<HarnessIntent.Public>()
    var onSend: suspend (HarnessIntent.Public) -> SendResult = { error("read-only admission") }
    override suspend fun send(intent: HarnessIntent.Public): SendResult {
        sent += intent
        return onSend(intent)
    }
}

internal class SchedulerAccessRegistry : MachineRegistry {
    val library = MutableStateFlow<SchedulerAccessMachine?>(null)
    var observations = 0

    @Suppress("UNCHECKED_CAST") // The fixture publishes only the harness key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = when (key) {
        HarnessMachineKey -> library.value as MachineRef<S, P, O>?
        else -> error("unexpected key")
    }

    @Suppress("UNCHECKED_CAST") // The only registration has the exact state/output types of its key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> {
        observations++
        return when (key) {
            HarnessMachineKey -> library as StateFlow<MachineRef<S, P, O>?>
            else -> error("unexpected key")
        }
    }

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = error("read-only admission")
}

internal class SchedulerAccessToggles : FeatureToggles {
    val enabled = MutableStateFlow(true)

    @Suppress("UNCHECKED_CAST") // Admission reads only the boolean harness flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == HarnessEnabled)
        return enabled as Flow<T>
    }
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}
