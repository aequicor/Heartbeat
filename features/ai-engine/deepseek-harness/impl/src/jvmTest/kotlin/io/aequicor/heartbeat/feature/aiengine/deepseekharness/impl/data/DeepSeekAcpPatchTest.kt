package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekAcpPatchTest {
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
