package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PiRuntimeReleaseTest {
    @Test
    fun `build pin selects one exact release without network metadata`() {
        val pin = readPiRuntimeRelease(metadata().byteInputStream(), "darwin-arm64")!!
        assertEquals("0.87.1", pin.version)
        assertEquals("pi", pin.executable)
        assertEquals("a".repeat(64), pin.artifact.download.sha256)
        assertNull(readPiRuntimeRelease(null))
    }

    @Test
    fun `changed source digest asset and platform are rejected`() {
        val invalid = listOf(
            metadata().replace("github.com", "untrusted.example"),
            metadata().replace("a".repeat(64), "bad-digest"),
            metadata().replace("asset=pi-darwin-arm64.tar.gz", "asset=another.tar.gz"),
            metadata().replace("target=darwin-arm64", "target=linux-x64"),
        )
        for (value in invalid) {
            assertFailsWith<IllegalArgumentException> { readPiRuntimeRelease(value.byteInputStream(), "darwin-arm64") }
        }
    }

    private fun metadata(): String = """
        version=0.87.1
        target=darwin-arm64
        asset=pi-darwin-arm64.tar.gz
        url=https://github.com/earendil-works/pi/releases/download/v0.87.1/pi-darwin-arm64.tar.gz
        sha256=${"a".repeat(64)}
        """.trimIndent()
}
