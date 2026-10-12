package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.isBrowserRequestAllowed
import org.cef.network.CefRequest.ResourceType
import org.cef.network.CefRequest.TransitionFlags
import org.cef.network.CefRequest.TransitionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrowserPolicyTest {
    @Test
    fun `history gestures do not fork a tab even when their original transition was a link`() {
        val policy = DesktopBrowserPolicy({ false }, { false }, {}, {}, {}, DesktopBrowserAgentGuard(), true)
        val link = TransitionType.TT_LINK
        try {
            link.addQualifier(TransitionFlags.TT_FORWARD_BACK_FLAG)
            assertFalse(policy.opensLinkInTab(true, true, false, "GET", link))
        } finally {
            link.removeQualifier(TransitionFlags.TT_FORWARD_BACK_FLAG)
        }
    }

    @Test
    fun `only clicked GET main links open adjacent tabs`() {
        val policy = DesktopBrowserPolicy({ false }, { false }, {}, {}, {}, DesktopBrowserAgentGuard(), true)
        assertTrue(policy.opensLinkInTab(true, true, false, "GET", TransitionType.TT_LINK))
        assertFalse(policy.opensLinkInTab(false, true, false, "GET", TransitionType.TT_LINK))
        assertFalse(policy.opensLinkInTab(true, false, false, "GET", TransitionType.TT_LINK))
        assertFalse(policy.opensLinkInTab(true, true, true, "GET", TransitionType.TT_LINK))
        assertFalse(policy.opensLinkInTab(true, true, false, "POST", TransitionType.TT_LINK))
        for (transition in listOf(
            TransitionType.TT_FORM_SUBMIT,
            TransitionType.TT_EXPLICIT,
            TransitionType.TT_RELOAD,
        )) {
            assertFalse(policy.opensLinkInTab(true, true, false, "GET", transition))
        }
    }

    @Test
    fun `resources initiated by the main frame are not main document navigations`() {
        listOf(ResourceType.RT_IMAGE, ResourceType.RT_SCRIPT, ResourceType.RT_XHR).forEach { type ->
            val kind = desktopBrowserRequestKind(isNavigation = false, isMainFrame = true, resourceType = type)
            assertEquals(BrowserRequestKind.Subresource, kind)
            assertTrue(isBrowserRequestAllowed("data:text/plain,resource", kind))
            assertFalse(isBrowserRequestAllowed("file:///etc/passwd", kind))
        }
    }

    @Test
    fun `main documents and main navigation preloads keep strict navigation policy`() {
        listOf(ResourceType.RT_MAIN_FRAME, ResourceType.RT_NAVIGATION_PRELOAD_MAIN_FRAME).forEach { type ->
            val kind = desktopBrowserRequestKind(isNavigation = false, isMainFrame = null, resourceType = type)
            assertEquals(BrowserRequestKind.MainDocument, kind)
            assertFalse(isBrowserRequestAllowed("data:text/html,document", kind))
        }
    }

    @Test
    fun `child documents and child preloads allow memory documents`() {
        listOf(ResourceType.RT_SUB_FRAME, ResourceType.RT_NAVIGATION_PRELOAD_SUB_FRAME).forEach { type ->
            val kind = desktopBrowserRequestKind(isNavigation = true, isMainFrame = false, resourceType = type)
            assertEquals(BrowserRequestKind.ChildDocument, kind)
            assertTrue(isBrowserRequestAllowed("blob:https://example.com/id", kind))
        }
    }

    @Test
    fun `navigation metadata is used when resource type is not available`() {
        assertEquals(BrowserRequestKind.MainDocument, desktopBrowserRequestKind(true, true, null))
        assertEquals(BrowserRequestKind.MainDocument, desktopBrowserRequestKind(true, null, null))
        assertEquals(BrowserRequestKind.ChildDocument, desktopBrowserRequestKind(true, false, null))
    }

    @Test
    fun `agent tab main documents stay on the public web except the approved origin`() {
        val guard = DesktopBrowserAgentGuard()
        var opened = 0
        var unsupported = 0
        val policy = DesktopBrowserPolicy(
            isReleased = { false },
            isBootstrap = { false },
            unsupported = { unsupported++ },
            open = { opened++ },
            unavailable = {},
            guard = guard,
        )
        val main = BrowserRequestKind.MainDocument
        val private = listOf(
            "http://192.168.1.1/",
            "http://localhost:8080/",
            "http://127.0.0.1/",
            "http://[::1]/",
            "https://printer.local/",
        )
        // A user tab keeps the plain scheme policy.
        private.forEach { assertTrue(policy.allows(it, main), it) }
        assertFalse(guard.consumeRefusal())

        guard.restrict(approved = null)
        assertTrue(policy.allows("https://example.com/", main))
        // Redirect hops, script and link navigations all arrive as main documents.
        private.forEach { assertFalse(policy.allows(it, main), it) }
        assertTrue(guard.consumeRefusal())
        assertFalse(guard.consumeRefusal())
        assertFalse(guard.consumeContentRefusal())
        // Plain resources are not documents the agent sees or acts on.
        assertTrue(policy.allows("http://192.168.1.1/image.png", BrowserRequestKind.Subresource))
        policy.popup("http://10.0.0.1/")
        policy.popup("https://example.com/")
        assertEquals(1, opened)
        assertEquals(1, unsupported)

        guard.restrict(approved = "http://192.168.1.1/admin")
        assertTrue(policy.allows("http://192.168.1.1/login", main))
        assertTrue(policy.allows("http://192.168.1.1:80/login", main))
        assertTrue(policy.allows("http://192.168.1.1./login", main))
        assertFalse(policy.allows("http://192.168.1.2/", main))
        assertFalse(policy.allows("http://localhost/", main))
        guard.restrict(approved = "https://example.com/")
        assertFalse(policy.allows("http://192.168.1.1/login", main))

        guard.unrestrict()
        assertTrue(policy.allows("http://192.168.1.1/login", main))
    }

    @Test
    fun `approval covers one origin, not the host on another port or scheme`() {
        val guard = DesktopBrowserAgentGuard()
        val policy = DesktopBrowserPolicy({ false }, { false }, {}, {}, {}, guard)
        val main = BrowserRequestKind.MainDocument
        guard.restrict(approved = "http://LOCALHOST:3000/app")
        assertTrue(policy.allows("http://localhost:3000/other", main))
        assertFalse(policy.allows("http://localhost/", main))
        assertFalse(policy.allows("http://localhost:3001/", main))
        assertFalse(policy.allows("https://localhost:3000/", main))

        guard.restrict(approved = "https://192.168.1.1/")
        assertTrue(policy.allows("https://192.168.1.1:443/", main))
        assertFalse(policy.allows("http://192.168.1.1/", main))
        assertFalse(policy.allows("https://192.168.1.1:8443/", main))
        // Credentials make the authority ambiguous: such an address never matches an approval.
        guard.restrict(approved = "http://a:b@10.0.0.1/")
        assertFalse(guard.allows("http://10.0.0.1/", main))
        guard.restrict(approved = "http://10.0.0.1/")
        assertFalse(guard.allows("http://a:b@10.0.0.1/", main))
    }

    @Test
    fun `agent tab iframes to private addresses are blocked without failing the page`() {
        val guard = DesktopBrowserAgentGuard().apply { restrict(approved = null) }
        var unsupported = 0
        val policy = DesktopBrowserPolicy({ false }, { false }, { unsupported++ }, {}, {}, guard)
        val child = BrowserRequestKind.ChildDocument
        assertTrue(policy.allows("https://example.com/embed", child))
        assertTrue(policy.allows("about:srcdoc", child))
        assertFalse(policy.allows("http://192.168.1.1/", child))
        assertFalse(policy.allows("http://localhost:8080/frame", child))
        assertFalse(policy.allows("https://[fd00::1]/frame", child))
        // Sockets never reach CEF request handlers (DesktopBrowserNativeTest); the guard does not claim to judge them.
        assertTrue(guard.allows("ws://192.168.1.1/socket", BrowserRequestKind.Subresource))
        // Embedded content is not a navigation of the tab: the agent hears about it separately.
        assertFalse(guard.consumeRefusal())
        assertTrue(guard.consumeContentRefusal())
        assertFalse(guard.consumeContentRefusal())
        assertEquals(0, unsupported)

        guard.restrict(approved = "http://192.168.1.1/")
        assertTrue(policy.allows("http://192.168.1.1/frame", child))
        assertFalse(policy.allows("http://192.168.1.2/frame", child))

        guard.unrestrict()
        assertTrue(policy.allows("http://10.0.0.1/", child))
    }

    @Test
    fun `agent guard leaves the engine's own blank bootstrap to the scheme policy`() {
        val guard = DesktopBrowserAgentGuard().apply { restrict(approved = null) }
        val policy = DesktopBrowserPolicy({ false }, { true }, {}, {}, {}, guard)
        assertTrue(policy.allows("about:blank", BrowserRequestKind.MainDocument))
        assertFalse(guard.consumeRefusal())
    }

    @Test
    fun `worker requests without frames remain resources`() {
        val kind = desktopBrowserRequestKind(false, null, ResourceType.RT_XHR)
        assertEquals(BrowserRequestKind.Subresource, kind)
        assertTrue(isBrowserRequestAllowed("wss://example.com/socket", kind))
        assertFalse(isBrowserRequestAllowed("intent://external", kind))
    }
}
