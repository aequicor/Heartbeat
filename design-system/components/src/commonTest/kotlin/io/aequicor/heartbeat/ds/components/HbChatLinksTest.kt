package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HbChatLinksTest {
    @Test
    fun `default handler opens external links and leaves local destinations to the caller`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val external = listOf("https://example.test", "http://example.test", "mailto:test@example.test")
        (external + listOf("/project/main.kt", "#heading", "javascript:alert(1)", "file:///project/main.kt"))
            .forEach { openChatLink(handler, it) }
        assertEquals(external, opened)
    }

    @Test
    fun `failure to open a browser does not crash the chat`() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                error("No browser available")
            }
        }
        openChatLink(handler, "https://example.test")
    }

    @Test
    fun `cancellation is propagated`() {
        val handler = object : UriHandler {
            override fun openUri(uri: String): Unit = throw CancellationException("Cancelled")
        }
        assertFailsWith<CancellationException> { openChatLink(handler, "https://example.test") }
    }
}
