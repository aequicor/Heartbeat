package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.isBrowserRequestAllowed
import org.cef.network.CefRequest.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrowserPolicyTest {
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
    fun `worker requests without frames remain resources`() {
        val kind = desktopBrowserRequestKind(false, null, ResourceType.RT_XHR)
        assertEquals(BrowserRequestKind.Subresource, kind)
        assertTrue(isBrowserRequestAllowed("wss://example.com/socket", kind))
        assertFalse(isBrowserRequestAllowed("intent://external", kind))
    }
}
