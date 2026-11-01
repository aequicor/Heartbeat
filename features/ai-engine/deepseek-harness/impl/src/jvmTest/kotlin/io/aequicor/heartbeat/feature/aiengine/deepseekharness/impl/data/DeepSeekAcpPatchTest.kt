package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekAcpPatchTest {
    @Test
    fun `binding plugin is inserted rather than targeting a nonexistent row`() {
        val overlay = DeepSeekAcpPatch.hostedOverlay("file:/tmp/heartbeat-hosted.mjs")
        val rows = org.yaml.snakeyaml.Yaml().load<List<Map<String, Any>>>(overlay)
        val insert = rows.last()["insert"] as List<*>
        val plugin = insert.single() as Map<*, *>
        assertEquals("heartbeat-hosted-binding", plugin["id"])
        assertEquals("file:/tmp/heartbeat-hosted.mjs", plugin["name"])
    }

    @Test
    fun `the overlay pins the official route with a multimodal model`() {
        assertTrue(DeepSeekAcpPatch.CONTENT.contains("provider: deepseek-official"))
        assertTrue(DeepSeekAcpPatch.CONTENT.contains("model: deepseek-flash"))
    }

    @Test
    fun `the overlay is passed to the dsh launcher when available`() {
        assertEquals(listOf("--profile", "acp"), dshAcpArguments(null))
        assertEquals(
            listOf("--profile", "acp", "--patch", "/tmp/heartbeat-dsh-acp.yml"),
            dshAcpArguments("/tmp/heartbeat-dsh-acp.yml"),
        )
    }
}
