package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
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
        val advertised = listOf(model.copy(reasoningEfforts = listOf("high")))
        assertEquals(listOf("high"), advertised.reasoningEfforts(target))
        assertEquals(emptyList(), advertised.reasoningEfforts(target.copy(binding = EngineBindingId("other"))))
        assertEquals(emptyList(), advertised.reasoningEfforts(target.copy(model = ModelId("other"))))
    }

    @Test
    fun `approval modes reach only engines that apply trust levels`() {
        val trusted = listOf(studioModel(target, null, "Engine", "Connection", true, isTrustSupported = true))
        assertEquals(TrustLevel.Ask, ApprovalMode.Ask.trustFor(trusted, target))
        assertEquals(TrustLevel.AutoEdits, ApprovalMode.AutoEdits.trustFor(trusted, target))
        assertEquals(TrustLevel.Full, ApprovalMode.AutoApprove.trustFor(trusted, target))
        val untrusted = listOf(studioModel(target, null, "Engine", "Connection", true))
        assertNull(ApprovalMode.AutoApprove.trustFor(untrusted, target))
        assertNull(ApprovalMode.AutoApprove.trustFor(trusted, target.copy(binding = EngineBindingId("other"))))
    }

    @Test
    fun `only engines switching a session's model share a connection key`() {
        val switching = studioModel(target, null, "Engine", "Connection", true, isModelSwitchSupported = true)
        assertEquals("engine/connection", switching.toUi().connectionKey)
        assertNull(studioModel(target, null, "Engine", "Connection", true).toUi().connectionKey)
    }

    @Test
    fun `native configuration without the legacy setter still exposes sibling models on its binding`() {
        val features = setOf(ChangesSessionConfiguration.id)
        val native = studioModel(
            target,
            null,
            "Engine",
            "Connection",
            true,
            isModelSwitchSupported = features.supportsStudioModelSwitch(),
        )
        assertEquals("engine/connection", native.toUi().connectionKey)
        assertTrue(setOf(SwitchesModels.id).supportsStudioModelSwitch())
        assertEquals(false, emptySet<EngineFeatureId>().supportsStudioModelSwitch())
    }
}
