package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepSeekNativeToolsTest {
    @Test
    fun `read tools are classified as reads`() {
        assertEquals(AgentToolAction.Read, dshNativeCall("read", null, "/ws").action)
        assertEquals(AgentToolAction.Read, dshNativeCall("web_search", null, "/ws").action)
        assertEquals(AgentToolAction.Read, dshNativeCall("grep", null, "/ws").action)
    }

    @Test
    fun `edit tools are classified as edits`() {
        assertEquals(AgentToolAction.Edit, dshNativeCall("write", null, "/ws").action)
        assertEquals(AgentToolAction.Edit, dshNativeCall("apply_patch", null, "/ws").action)
    }

    @Test
    fun `unknown and shell tools are classified as commands`() {
        assertEquals(AgentToolAction.Command, dshNativeCall("bash", null, "/ws").action)
        assertEquals(AgentToolAction.Command, dshNativeCall("pwsh", null, "/ws").action)
        assertEquals(AgentToolAction.Command, dshNativeCall("unknown_tool", null, "/ws").action)
    }

    @Test
    fun `paths and command are extracted from arguments`() {
        val args = buildJsonObject {
            put("path", "/ws/a.txt")
            put("command", "echo hi")
        }
        val call = dshNativeCall("write", args, "/ws")
        assertEquals("write", call.name)
        assertEquals(listOf("/ws/a.txt"), call.paths)
        assertEquals("echo hi", call.command)
        assertEquals("/ws", call.workingDirectory)
    }
}
