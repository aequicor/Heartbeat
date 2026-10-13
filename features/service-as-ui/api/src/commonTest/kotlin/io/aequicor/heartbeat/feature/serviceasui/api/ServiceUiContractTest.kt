package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ServiceUiContractTest {
    @Test
    fun `ids accept slugs and reject everything else`() {
        assertEquals("harness:h_1:pr_map", UiSurfaceId.of(UiOwner("harness", "h_1"), UiSurfaceName("pr_map")).value)
        assertEquals("aistudio.pane.3", UiHostId("aistudio.pane.3").value)
        assertEquals("aistudio", UiDockId("aistudio").value)

        for (name in listOf("", "Map", "pr-map", "1map", "m".repeat(33))) {
            assertFailsWith<IllegalArgumentException> { UiSurfaceName(name) }
        }
        for ((kind, id) in listOf("Harness" to "h", "harness" to " ", "harness" to "a:b", "h" to "x".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { UiOwner(kind, id) }
        }
        for (place in listOf("", "Pane", " pane", "-pane", "p".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { UiHostId(place) }
            assertFailsWith<IllegalArgumentException> { UiDockId(place) }
        }
    }

    @Test
    fun `slots map to inline placements`() {
        assertEquals(UiPlacement.Composer, UiSlot.Composer.placement)
        assertEquals(UiPlacement.Start, UiSlot.Start.placement)
        assertEquals(listOf(UiPlacement.Composer, UiPlacement.Start), UiPlacement.entries.filter { it.isInline })
    }

    @Test
    fun `shown card ids round trip through the show result`() {
        val result = ServiceUiTools.shown("uc_0123abcd")

        assertEquals("uc_0123abcd", ServiceUiTools.shownCardId(ServiceUiTools.SHOW, result))
        assertNull(ServiceUiTools.shownCardId(ServiceUiTools.SHOW, "Result: $result"))
        assertNull(ServiceUiTools.shownCardId(ServiceUiTools.PUT, result))
        assertNull(ServiceUiTools.shownCardId(ServiceUiTools.SHOW, "Card limit for this chat reached"))
        assertFailsWith<IllegalArgumentException> { ServiceUiTools.shown("Bad Id") }
    }

    @Test
    fun `call data never appears in text`() {
        val secret = "private-value"
        val context = UiCallContext(
            session = SessionRef(EngineId("pi"), SessionSourceId("local"), secret),
            workspace = WorkspaceRef("/home/$secret"),
            preselected = mapOf("harness" to setOf(secret)),
        )
        val call = UiMethodCall("prs", buildJsonObject { put("token", secret) }, context)
        val texts = listOf(
            context.toString(),
            call.toString(),
            UiMethodResult.Success(JsonPrimitive(secret)).toString(),
            UiMethodResult.UserError(secret).toString(),
        )

        for (text in texts) assertFalse(secret in text, text)
        assertEquals("UiCallContext(hasSession=true, hasWorkspace=true, preselected={harness=1})", context.toString())
    }
}
