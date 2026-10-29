package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class KimiNativeToolsTest {
    @Test
    fun `read and edit calls expose their target paths to profile rules`() {
        val read = kimiNativeCall(
            buildJsonObject {
                put("kind", "read")
                put("rawInput", buildJsonObject { put("path", "src/A.kt") })
            },
            "/workspace",
        )
        assertEquals("read", read.name)
        assertEquals(AgentToolAction.Read, read.action)
        assertEquals(listOf("src/A.kt"), read.paths)

        val edit = kimiNativeCall(
            buildJsonObject {
                put("kind", "edit")
                put("rawInput", buildJsonObject { put("file_path", "src/B.kt") })
            },
            "/workspace",
        )
        assertEquals("edit", edit.name)
        assertEquals(AgentToolAction.Edit, edit.action)
        assertEquals(listOf("src/B.kt"), edit.paths)
    }

    @Test
    fun `kind falls back to the native tool name and execute reports no paths`() {
        val named = kimiNativeCall(
            buildJsonObject {
                put("kind", "")
                put("name", "read")
                put("rawInput", JsonObject(mapOf("path" to JsonPrimitive("a.txt"))))
            },
            "/workspace",
        )
        assertEquals("read", named.name)
        assertEquals(AgentToolAction.Read, named.action)
        assertEquals(listOf("a.txt"), named.paths)

        val execute = kimiNativeCall(
            buildJsonObject {
                put("kind", "execute")
                put("rawInput", buildJsonObject { put("command", "ls") })
            },
            "/workspace",
        )
        assertEquals("execute", execute.name)
        assertEquals(AgentToolAction.Command, execute.action)
        assertEquals("ls", execute.command)
        assertEquals(emptyList(), execute.paths)
    }
}
