package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineStudioModelOptionsTest {
    private val target = EngineTarget(EngineId("engine"), EngineBindingId("connection"), ModelId("model-id"))

    @Test
    fun `catalog supplies compact display identity and advertised effort options`() {
        val info = ModelInfo(target, "Model", reasoningEfforts = listOf("future"), defaultReasoningEffort = "future")
        val model = studioModel(target, info, "Engine", "My connection", false)
        assertEquals("Model", model.shortName)
        assertEquals("Engine · Model · My connection", model.name)
        assertEquals(listOf("future"), model.reasoningEfforts)
        assertEquals("future", model.defaultReasoningEffort)
    }

    @Test
    fun `unknown capabilities stay hidden and preferences do not cross credential routes`() {
        val model = studioModel(target, null, "Engine", "Connection", false)
        assertEquals("model-id", model.shortName)
        assertTrue(model.reasoningEfforts.isEmpty())
        val advertised = model.copy(reasoningEfforts = listOf("high"))
        val settings = DefaultRunSettings.copy(engineEfforts = mapOf(model.id to "high"))
        assertEquals("high", settings.reasoningEffort(target, listOf(advertised)))
        assertNull(settings.reasoningEffort(target.copy(binding = EngineBindingId("other")), listOf(advertised)))
        assertNull(settings.reasoningEffort(target.copy(model = ModelId("other")), listOf(advertised)))
    }

    @Test
    fun `withdrawn reasoning capabilities restore the native default instead of sending a hidden override`() {
        val model = studioModel(target, null, "Engine", "Connection", false)
        val settings = DefaultRunSettings.copy(engineEfforts = mapOf(model.id to "high"))
        assertNull(settings.reasoningEffort(target, listOf(model)))
        assertNull(settings.reasoningEffort(target, listOf(model.copy(reasoningEfforts = listOf("low")))))
    }
}
