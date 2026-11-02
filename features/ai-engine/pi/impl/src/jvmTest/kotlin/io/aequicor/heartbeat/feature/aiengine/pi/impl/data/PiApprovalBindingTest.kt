package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PiApprovalBindingTest {
    @Test
    fun `identical titles retain distinct argument and executor snapshots`() {
        val call = PiApprovalCall("write", "same target", arguments = JsonObject(mapOf("text" to JsonPrimitive("A"))))
        val first = approvalRequest("ask", TurnId("turn"), call, false)
        val changed = approvalRequest(
            "ask",
            TurnId("turn"),
            call.copy(arguments = JsonObject(mapOf("text" to JsonPrimitive("B")))),
            false,
        )
        val executor = approvalRequest("ask", TurnId("turn"), call.copy(executorBinding = "other"), false)
        assertEquals(first.title, changed.title)
        assertNotEquals(first.operationBinding, changed.operationBinding)
        assertNotEquals(first.operationBinding, executor.operationBinding)
        assertEquals("PermissionRequest", first.toString())
        assertEquals("host", approvalRequest("ask", TurnId("turn"), call, false, "host").operationBinding)
    }
}
