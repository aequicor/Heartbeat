package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexEngineVersionTest {
    @Test
    fun `handshake agent names the version of the held process`() {
        assertEquals(
            "0.166.0-alpha.5.1",
            codexEngineVersion("heartbeat/0.166.0-alpha.5.1 (Mac OS 26.6.2; arm64) unknown (heartbeat; 0.1.0)"),
        )
        assertEquals("0.160.0", codexEngineVersion("codex_app_server/0.160.0 (Linux; x86_64)"))
    }

    @Test
    fun `absent or foreign payloads stay unknown instead of becoming a version`() {
        listOf(null, "", "   ", "unknown", "codex-cli 0.160.0", "codex/", "0.160.0").forEach { agent ->
            assertNull(codexEngineVersion(agent), "unexpected version for $agent")
        }
    }
}
