package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cef.callback.CefResourceReadCallback
import org.cef.misc.IntRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDocumentPageTest {
    @Test
    fun `the page API comes first, after a doctype that keeps standards mode`() {
        val withDoctype = desktopDocumentHtml("\uFEFF  <!DOCTYPE html><p>Hi</p>", null, null)
        assertTrue(withDoctype.startsWith("  <!DOCTYPE html><script>"), withDoctype)
        assertTrue(withDoctype.endsWith("</script><p>Hi</p>"))
        val bare = desktopDocumentHtml("<p>Hi</p>", null, null)
        assertTrue(bare.startsWith("<script>") && bare.endsWith("</script><p>Hi</p>"))
    }

    @Test
    fun `data cannot close the script or break its string literals`() {
        val data = buildJsonObject { put("text", "</script><script>alert(1)</script>\u2028<!--") }
        val page = desktopDocumentHtml("<p/>", data, null)
        assertEquals(1, Regex("</script>").findAll(page).count())
        assertFalse("<!--" in page)
        assertFalse('\u2028' in page)
        assertTrue("\\u003c/script>" in page)
        val update = desktopDocumentDataScript(JsonPrimitive("</script>"))
        assertEquals("window.__heartbeatData && window.__heartbeatData(\"\\u003c/script>\");", update)
    }

    @Test
    fun `only bridged pages reach the router function`() {
        assertTrue("const query = null;" in desktopDocumentHtml("<p/>", null, null))
        val bridged = desktopDocumentHtml("<p/>", null, "__hbQueryabc")
        assertTrue("const query = window[\"__hbQueryabc\"];" in bridged)
    }

    @Test
    fun `documents have their own unresolvable origin and a policy without network`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals("https://$id.document.heartbeat.invalid/", desktopDocumentUrl(id))
        val csp = desktopDocumentHeaders.getValue("Content-Security-Policy")
        listOf(
            "default-src 'none'",
            "connect-src 'none'",
            "frame-src 'none'",
            "worker-src 'none'",
            "form-action 'none'",
        )
            .forEach { assertTrue(it in csp, it) }
        assertFalse("http" in csp)
        assertEquals("no-store", desktopDocumentHeaders["Cache-Control"])
    }

    @Test
    fun `a response carries a byte order mark and is read in parts until its end`() {
        val body = desktopDocumentBody("\uFEFFé")
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "é".encodeToByteArray(), body)
        val response = DesktopDocumentResponse(body)
        val read = IntRef()
        val buffer = ByteArray(3)
        val callback = object : CefResourceReadCallback {
            override fun Continue(bytesRead: Int) = Unit

            override fun getBuffer(): ByteArray = buffer
        }
        assertTrue(response.read(buffer, buffer.size, read, callback))
        assertEquals(3, read.get())
        assertTrue(response.read(buffer, buffer.size, read, callback))
        assertEquals(2, read.get())
        assertFalse(response.read(buffer, buffer.size, read, callback))
        assertEquals(0, read.get())
    }

    @Test
    fun `only the main frame's navigation to its own address is the document`() {
        val url = desktopDocumentUrl("a")
        assertTrue(isDesktopDocumentRequest(url, url, isNavigation = true, isMainFrame = true, isDownload = false))
        assertFalse(isDesktopDocumentRequest(url, url, isNavigation = false, isMainFrame = true, isDownload = false))
        assertFalse(isDesktopDocumentRequest(url, url, isNavigation = true, isMainFrame = false, isDownload = false))
        assertFalse(isDesktopDocumentRequest(url, url, isNavigation = true, isMainFrame = null, isDownload = false))
        assertFalse(isDesktopDocumentRequest(url, url, isNavigation = true, isMainFrame = true, isDownload = true))
        assertFalse(
            isDesktopDocumentRequest(url, "${url}x", isNavigation = true, isMainFrame = true, isDownload = false),
        )
        assertFalse(isDesktopDocumentRequest(url, desktopDocumentUrl("b"), true, true, false))
        assertTrue(
            allowsDesktopDocumentNavigation(url, url, isMainFrame = true, isRedirect = false, isBootstrap = false),
        )
        assertFalse(
            allowsDesktopDocumentNavigation(url, url, isMainFrame = false, isRedirect = false, isBootstrap = true),
        )
        assertFalse(
            allowsDesktopDocumentNavigation(url, url, isMainFrame = true, isRedirect = true, isBootstrap = true),
        )
        assertTrue(allowsDesktopDocumentNavigation(url, "about:blank", true, isRedirect = false, isBootstrap = true))
        assertFalse(allowsDesktopDocumentNavigation(url, "about:blank", true, isRedirect = false, isBootstrap = false))
        assertFalse(allowsDesktopDocumentNavigation(url, "https://example.com/", true, false, isBootstrap = true))
        assertTrue(isDesktopDocumentFrame(url, url))
        assertTrue(isDesktopDocumentFrame(url, "$url#section"))
        assertTrue(isDesktopDocumentFrame(url, "${url}page?view=1"))
        assertFalse(isDesktopDocumentFrame(url, desktopDocumentUrl("b")))
        assertFalse(isDesktopDocumentFrame(url, null))
    }
}
