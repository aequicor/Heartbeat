package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextOnlyServiceRequestTest {
    private val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))

    @Test
    fun `service creation rejects inherited project and feature context`() {
        val service = CreateSessionRequest(target, isTextOnlyService = true)
        assertTrue(service.isTextOnlyService)
        assertFailsWith<IllegalArgumentException> { service.copy(workspace = WorkspaceRef("project")) }
        assertFailsWith<IllegalArgumentException> { service.copy(areDetachedToolsEnabled = true) }
        assertFailsWith<IllegalArgumentException> { service.copy(preselected = mapOf("harness" to setOf("one"))) }
    }

    @Test
    fun `legacy requests keep normal hook behavior and service mode survives serialization`() {
        val original = CreateSessionRequest(target, areSessionHooksEnabled = true)
        val legacy = Json.encodeToString(CreateSessionRequest.serializer(), original)
        assertFalse("isTextOnlyService" in legacy)
        assertEquals(original, Json.decodeFromString(CreateSessionRequest.serializer(), legacy))
        val service = original.copy(isTextOnlyService = true)
        val encoded = Json.encodeToString(CreateSessionRequest.serializer(), service)
        assertEquals(service, Json.decodeFromString(CreateSessionRequest.serializer(), encoded))
    }
}
