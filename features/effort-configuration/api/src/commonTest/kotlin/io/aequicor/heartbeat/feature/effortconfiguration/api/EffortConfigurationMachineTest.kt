package io.aequicor.heartbeat.feature.effortconfiguration.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffortConfigurationMachineTest {
    private val spec = EffortConfigurationMachineSpec
    private val target = EngineTarget(EngineId("codex"), EngineBindingId("default"), ModelId("gpt"))
    private val other = target.copy(model = ModelId("other"))
    private val choice = EffortChoice(target, "high")

    @Test
    fun `start loads stored choices`() {
        spec.assertTransition(
            EffortConfigurationState.Idle,
            EffortConfigurationIntent.Public.Start,
            EffortConfigurationState.Loading,
            effects = listOf(EffortConfigurationEffect.Load),
        )
        spec.assertTransition(
            EffortConfigurationState.Loading,
            EffortConfigurationIntent.Internal.Loaded(listOf(choice, choice.copy(effort = "low"))),
            EffortConfigurationState.Ready(listOf(choice)),
        )
        spec.assertTransition(
            EffortConfigurationState.Loading,
            EffortConfigurationIntent.Internal.LoadFailed,
            EffortConfigurationState.Ready(),
        )
    }

    @Test
    fun `select replaces the target choice and saves`() {
        val ready = EffortConfigurationState.Ready(listOf(choice, EffortChoice(other, "low")))
        val updated = listOf(EffortChoice(other, "low"), EffortChoice(target, "medium"))
        spec.assertTransition(
            ready,
            EffortConfigurationIntent.Public.Select(target, "medium"),
            EffortConfigurationState.Ready(updated, revision = 1),
            effects = listOf(EffortConfigurationEffect.Save(updated, revision = 1)),
        )
        val reset = listOf(EffortChoice(other, "low"))
        spec.assertTransition(
            ready,
            EffortConfigurationIntent.Public.Select(target, null),
            EffortConfigurationState.Ready(reset, revision = 1),
            effects = listOf(EffortConfigurationEffect.Save(reset, revision = 1)),
        )
    }

    @Test
    fun `unchanged or premature selection is ignored`() {
        val select = EffortConfigurationIntent.Public.Select(target, "high")
        spec.assertIgnored(EffortConfigurationState.Ready(listOf(choice)), select)
        spec.assertIgnored(EffortConfigurationState.Ready(), EffortConfigurationIntent.Public.Select(target, null))
        spec.assertIgnored(EffortConfigurationState.Idle, select)
        spec.assertIgnored(EffortConfigurationState.Loading, select)
    }

    @Test
    fun `save failure keeps the selection and notifies`() {
        val ready = EffortConfigurationState.Ready(listOf(choice))
        spec.assertTransition(
            ready,
            EffortConfigurationIntent.Internal.SaveFailed,
            ready,
            outputs = listOf(EffortConfigurationOutput.SaveFailed),
        )
    }

    @Test
    fun `effective effort requires an advertised level`() {
        val ready = EffortConfigurationState.Ready(listOf(choice))
        val supported = listOf("low", "high")
        assertEquals("high", ready.effectiveEffort(target, supported))
        assertNull(ready.effectiveEffort(target, listOf("low")))
        assertNull(ready.effectiveEffort(target, emptyList()))
        assertNull(ready.effectiveEffort(other, supported))
        assertNull(EffortConfigurationState.Loading.effectiveEffort(target, supported))
    }
}
