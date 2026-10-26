package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClaudeNativeWorkspaceTest {
    private fun url(value: String) = JsonObject(mapOf("url" to JsonPrimitive(value)))

    @Test fun `a virtual workspace runs only web fetch`() {
        val virtual = ClaudeNativeWorkspace(null)
        assertEquals(setOf("WebFetch"), virtual.supported(setOf("Bash", "Read", "WebFetch")))
        assertNull(virtual.classify("Bash", JsonObject(mapOf("command" to JsonPrimitive("ls")))))
        assertNotNull(virtual.classify("WebFetch", url("https://github.com/aequicor/Heartbeat/pull/529")))
    }

    @Test fun `web fetch of a local or private address never reaches the gate`() {
        val workspace = ClaudeNativeWorkspace("/project")
        listOf(
            "http://localhost:8080/",
            "http://127.0.0.1/",
            "http://192.168.0.1/x",
            "file:///etc/passwd",
            "",
        ).forEach {
            assertNull(workspace.classify("WebFetch", url(it)), it)
        }
    }
}
