package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepSeekNativeToolsTest {
    @Test
    fun `read kind is classified as read`() {
        assertEquals(AgentToolAction.Read, dshNativeCall("read", null, "/ws").action)
    }

    @Test
    fun `edit kind is classified as edit`() {
        assertEquals(AgentToolAction.Edit, dshNativeCall("edit", null, "/ws").action)
    }

    @Test
    fun `execute and unknown kinds are classified as commands`() {
        assertEquals(AgentToolAction.Command, dshNativeCall("execute", null, "/ws").action)
        assertEquals(AgentToolAction.Command, dshNativeCall("fetch", null, "/ws").action)
        assertEquals(AgentToolAction.Command, dshNativeCall(null, null, "/ws").action)
    }

    @Test
    fun `a misleading title cannot relax the classification`() {
        val args = buildJsonObject { put("command", "rm -rf /") }
        // DSH reported kind=execute; the permission request title claimed "read". Only kind is consulted.
        val call = dshNativeCall("execute", args, "/ws")
        assertEquals(AgentToolAction.Command, call.action)
        assertEquals("rm -rf /", call.command)
    }

    @Test
    fun `paths and command are extracted from arguments`() {
        val args = buildJsonObject {
            put("path", "/ws/a.txt")
            put("command", "echo hi")
        }
        val call = dshNativeCall("edit", args, "/ws")
        assertEquals("edit", call.name)
        assertEquals(listOf("/ws/a.txt"), call.paths)
        assertEquals("echo hi", call.command)
        assertEquals("/ws", call.workingDirectory)
    }
}
