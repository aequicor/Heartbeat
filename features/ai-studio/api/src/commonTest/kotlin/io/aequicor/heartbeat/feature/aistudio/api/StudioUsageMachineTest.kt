package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import kotlin.test.Test

class StudioUsageMachineTest {
    private val ready = AiStudioState.Ready(
        listOf(StudioPane(0)),
        0,
        RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask),
    )

    @Test
    fun `usage follows profile telemetry and disappears when observation is lost`() {
        val contexts = mapOf("session" to ContextUsage(610, 1000))
        val providers = mapOf("model" to ProviderUsageSnapshot(listOf(ProviderUsageWindow("weekly", "Weekly", 99.0))))
        val observed = ready.copy(contexts = contexts, providerUsage = providers)
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Internal.RuntimeChanged(
                StudioRuntimeState(contexts = contexts, providerUsage = providers),
            ),
            to = observed,
        )
        AiStudioMachineSpec.assertTransition(from = observed, intent = AiStudioIntent.Internal.RuntimeLost, to = ready)
    }

    @Test
    fun `visible routes and panel refresh request effects without changing conversation state`() {
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Public.ObserveUsageTargets(setOf("a", "b")),
            to = ready,
            effects = listOf(AiStudioEffect.ObserveUsageTargets(setOf("a", "b"))),
        )
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Public.RefreshUsage("a"),
            to = ready,
            effects = listOf(AiStudioEffect.RefreshUsage("a")),
        )
        AiStudioMachineSpec.assertIgnored(AiStudioState.Disabled, AiStudioIntent.Public.RefreshUsage("a"))
    }
}
