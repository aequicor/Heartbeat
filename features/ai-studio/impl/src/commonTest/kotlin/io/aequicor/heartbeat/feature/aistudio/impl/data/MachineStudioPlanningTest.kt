package io.aequicor.heartbeat.feature.aistudio.impl.data

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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineKey
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class MachineStudioPlanningTest {
    private val session = SessionRef(EngineId("pi"), SessionSourceId("pi"), "native")
    private val view = View()
    private val registry = Registry()
    private val toggle = Toggle()

    private fun TestScope.planning() = MachineStudioPlanning(lazyOf(view), registry, toggle, TestClock(this))

    private fun plan(chat: String, mode: PlanMode = PlanMode.Planning) =
        Plan(PlanChat(chat), mode, updatedAt = Instant.fromEpochSeconds(0))

    private fun ready(vararg plans: Plan, bindings: Map<SessionRef, PlanChat> = emptyMap()) =
        PlanningState.Ready(plans.associateBy { it.chat }, bindings)

    @Test
    fun `nothing reaches the machine while the toggle is off`() = runTest {
        toggle.isOn.value = false
        view.state.value = ready(plan("chat"))
        val planning = planning()
        planning.bind("chat", session)
        assertFalse(planning.canEnter())
        assertFalse(planning.enter("chat"))
        assertFalse(planning.leave("chat"))
        assertFalse(planning.approve("chat", 0))
        assertFalse(planning.write("chat", "text", 0))
        assertFalse(planning.startImplementation("chat", 0))
        assertTrue(registry.sent.isEmpty())
    }

    @Test
    fun `plans still loading after the wait fail the turn and refuse commands`() = runTest {
        view.state.value = PlanningState.Loading
        val planning = planning()
        assertFailsWith<IllegalStateException> { planning.bind("chat", session) }
        assertFalse(planning.canEnter())
        assertFalse(planning.enter("chat"))
        assertTrue(registry.sent.isEmpty())
    }

    @Test
    fun `unreadable plans let the turn go because the hook asks for every session`() = runTest {
        view.state.value = PlanningState.Failed
        val planning = planning()
        planning.bind("chat", session)
        planning.bind("chat", session)
        assertFalse(planning.approve("chat", 0))
        assertTrue(registry.sent.isEmpty())
    }

    @Test
    fun `a chat already planning enters without a command`() = runTest {
        view.state.value = ready(plan("chat"))
        assertTrue(planning().enter("chat"))
        assertTrue(registry.sent.isEmpty())
    }

    @Test
    fun `a refused command reports false`() = runTest {
        view.state.value = ready()
        registry.result = SendResult.Ignored
        val planning = planning()
        assertFalse(planning.enter("chat"))
        assertFalse(planning.approve("chat", 1))
        registry.result = SendResult.Accepted
        assertTrue(planning.write("chat", "text", 1))
        assertEquals(3, registry.sent.size)
        assertIs<PlanningIntent.Public.Write>(registry.sent.last())
    }

    @Test
    fun `room for a new plan includes a detached plan to evict`() = runTest {
        val full = (0 until PlanLimits.MAX_PLANS).map { plan("c$it", PlanMode.Implementing) }
        view.state.value = ready(*full.toTypedArray())
        assertFalse(planning().canEnter())
        view.state.value = ready(*(full.drop(1) + plan("old", PlanMode.Detached)).toTypedArray())
        assertTrue(planning().canEnter())
        view.state.value = ready()
        assertTrue(planning().canEnter())
    }

    @Test
    fun `a chat without a plan or already bound is not bound again`() = runTest {
        view.state.value = ready(plan("other"), plan("chat"), bindings = mapOf(session to PlanChat("chat")))
        val planning = planning()
        planning.bind("plain", session)
        planning.bind("chat", session)
        assertTrue(registry.sent.isEmpty())
    }

    @Test
    fun `the plan follows the chat into its session`() = runTest {
        view.state.value = ready(plan("chat"))
        planning().bind("chat", session)
        val bound: List<PlanningIntent.Public> = listOf(PlanningIntent.Public.Bind(PlanChat("chat"), session))
        assertEquals(bound, registry.sent)
    }

    @Test
    fun `a planning chat whose session cannot be bound fails the turn`() = runTest {
        view.state.value = ready(plan("chat"))
        registry.result = SendResult.NotRunning
        assertFailsWith<IllegalStateException> { planning().bind("chat", session) }
    }

    @Test
    fun `an implementing chat whose session cannot be bound still runs`() = runTest {
        view.state.value = ready(plan("chat", PlanMode.Implementing))
        registry.result = SendResult.Ignored
        planning().bind("chat", session)
        assertEquals(1, registry.sent.size)
    }

    private class View : PlanningView {
        override val state = MutableStateFlow<PlanningState>(PlanningState.Idle)
    }

    private class Toggle : FeatureToggles {
        val isOn = MutableStateFlow(true)

        @Suppress("UNCHECKED_CAST") // Only the Boolean planning flag is read.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
            if (toggle == PlanningModeEnabled) isOn as Flow<T> else MutableStateFlow(toggle.default)

        @Suppress("UNCHECKED_CAST") // Only the Boolean planning flag is read.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
            (if (toggle == PlanningModeEnabled) isOn.value else toggle.default) as T
    }

    private class Registry : MachineRegistry {
        val sent = mutableListOf<PlanningIntent.Public>()
        var result = SendResult.Accepted

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = null

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(null)

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult {
            check(key == PlanningMachineKey) { "only the planning machine is addressed" }
            sent += intent as PlanningIntent.Public
            return result
        }
    }
}
