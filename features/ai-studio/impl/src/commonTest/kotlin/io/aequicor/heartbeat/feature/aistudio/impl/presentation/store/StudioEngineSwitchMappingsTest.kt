package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioEngineSwitchMappingsTest {
    private val source = EngineTarget(EngineId("a"), EngineBindingId("one"), ModelId("model"))

    @Test
    fun `same binding and disabled flag retain native settings intent`() {
        val model = StudioSettingChange.Model(source.copy(model = ModelId("other")).studioModelId())
        assertEquals(AiStudioIntent.Public.ChangeSessionSetting("chat", model), intent(model, true))
        val binding = StudioSettingChange.Model(source.copy(binding = EngineBindingId("two")).studioModelId())
        assertEquals(AiStudioIntent.Public.ChangeSessionSetting("chat", binding), intent(binding, false))
    }

    @Test
    fun `another engine or connection carries the menu snapshot as expected route`() {
        for (target in listOf(source.copy(engine = EngineId("b")), source.copy(binding = EngineBindingId("two")))) {
            assertEquals(
                AiStudioIntent.Public.SwitchSessionEngine("chat", StudioEngineSwitch(source, target)),
                intent(StudioSettingChange.Model(target.studioModelId()), true),
            )
        }
    }

    private fun intent(change: StudioSettingChange, enabled: Boolean) =
        sessionSettingIntent("chat", change, source.studioModelId(), enabled)
}
