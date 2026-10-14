package io.aequicor.heartbeat.feature.browser.impl.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeBrowserX11OwnershipTest {
    @Test
    fun `embedded Chromium child may own focus without AWT frame focus`() {
        val ownership = classifyX11Ownership(100, emptySet(), listOf(300, 200, 100, 10), 100, true)

        assertTrue(ownership.permitsInput(areOwnedDialogsAllowed = false))
    }

    @Test
    fun `owned popup focus requires explicit permission even when the WM keeps the owner active`() {
        val ownership = classifyX11Ownership(100, setOf(200), listOf(300, 200, 10), 100, true)

        assertTrue(ownership.permitsInput(areOwnedDialogsAllowed = true))
        assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = false))
    }

    @Test
    fun `WM activation of an owned popup is accepted only for owned dialog input`() {
        val ownership = classifyX11Ownership(100, setOf(200), listOf(300, 200, 10), 200, true)

        assertTrue(ownership.permitsInput(areOwnedDialogsAllowed = true))
        assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = false))
    }

    @Test
    fun `foreign active window blocks stale focus in the test window`() {
        val ownership = classifyX11Ownership(100, setOf(200), listOf(300, 100, 10), 400, true)

        assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = true))
    }

    @Test
    fun `active popup blocks stale focus behind it in the owning frame`() {
        val ownership = classifyX11Ownership(100, setOf(200), listOf(300, 100, 10), 200, true)

        assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = false))
        assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = true))
    }

    @Test
    fun `foreign or unavailable focus does not inherit permission from the active test window`() {
        for (ancestors in listOf(null, emptyList(), listOf(300L, 400L, 10L))) {
            val ownership = classifyX11Ownership(100, setOf(200), ancestors, 100, true)
            assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = true))
        }
    }

    @Test
    fun `unmapped or offscreen test windows do not authorize physical input`() {
        val ownership = classifyX11Ownership(100, emptySet(), listOf(300, 100, 10), 100, false)

        assertFalse(ownership.permitsInput(areOwnedDialogsAllowed = true))
    }
}
