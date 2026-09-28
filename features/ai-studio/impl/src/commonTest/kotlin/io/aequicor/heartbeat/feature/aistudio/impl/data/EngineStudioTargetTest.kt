package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineStudioTargetTest {
    private val stored = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("old"))
    private val default = stored.copy(model = ModelId("default"))

    @Test
    fun `model chosen for the turn overrides the stored chat route`() {
        val requested = stored.copy(model = ModelId("new"))
        val modelId = Json.encodeToString(EngineTarget.serializer(), requested)
        assertEquals(requested, turnTarget(modelId, stored, default))
    }

    @Test
    fun `without a choice the chat keeps its route and a new chat takes the default`() {
        assertEquals(stored, turnTarget("", stored, default))
        assertEquals(default, turnTarget(" ", null, default))
        assertNull(turnTarget("", null, null))
    }
}
