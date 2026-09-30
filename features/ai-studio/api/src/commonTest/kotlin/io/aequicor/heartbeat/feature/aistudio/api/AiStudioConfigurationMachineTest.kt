package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import kotlin.test.Test

class AiStudioConfigurationMachineTest {
    private val settings = RunSettings("model", ReasoningEffort.Medium, ApprovalMode.Ask)
    private val ready = AiStudioState.Ready(listOf(StudioPane(0, "first"), StudioPane(1, "second")), 0, settings)
    private val change = StudioSettingChange.Approval(ApprovalMode.AutoApprove)

    @Test
    fun `a running session parameter is addressed without changing global defaults`() {
        val running = ready.copy(running = setOf("first", "second"))
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Public.ChangeSessionSetting("second", change),
            to = running,
            effects = listOf(AiStudioEffect.ChangeSessionSetting("second", change)),
        )
    }

    @Test
    fun `another change is refused while this session configuration is pending`() {
        val pending = StudioSessionConfiguration(StudioSessionSettings("model"), "operation")
        AiStudioMachineSpec.assertIgnored(
            ready.copy(configurations = mapOf("first" to pending)),
            AiStudioIntent.Public.ChangeSessionSetting("first", change),
        )
        AiStudioMachineSpec.assertTransition(
            from = ready.copy(configurations = mapOf("first" to pending)),
            intent = AiStudioIntent.Public.ChangeSessionSetting("second", change),
            to = ready.copy(configurations = mapOf("first" to pending)),
            effects = listOf(AiStudioEffect.ChangeSessionSetting("second", change)),
        )
    }

    @Test
    fun `hidden or stopping sessions do not accept composer changes`() {
        AiStudioMachineSpec.assertIgnored(ready, AiStudioIntent.Public.ChangeSessionSetting("hidden", change))
        AiStudioMachineSpec.assertIgnored(
            ready.copy(stopping = setOf("first")),
            AiStudioIntent.Public.ChangeSessionSetting("first", change),
        )
    }

    @Test
    fun `profile runtime configurations and usage are reflected without updating defaults`() {
        val configurations = mapOf("first" to StudioSessionConfiguration(StudioSessionSettings("other")))
        val contexts = mapOf("first" to ContextUsage(10, 100))
        val providers = mapOf("other" to ProviderUsageSnapshot(listOf(ProviderUsageWindow("daily", "Daily", 20.0))))
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Internal.RuntimeChanged(
                StudioRuntimeState(configurations = configurations, contexts = contexts, providerUsage = providers),
            ),
            to = ready.copy(configurations = configurations, contexts = contexts, providerUsage = providers),
        )
    }
}
