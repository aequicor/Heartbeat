package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HbLinuxDocumentFocusTest {
    @Test
    fun `native document focus can return to its active mapped owner`() {
        assertTrue(canRestoreX11DocumentFocus(10, 10, listOf(30L, 20L, 10L, 2L), true))
    }

    @Test
    fun `document focus cannot activate its owner after the user switches windows`() {
        for (active in listOf(0L, 1L, 20L, 99L)) {
            assertFalse(canRestoreX11DocumentFocus(10, active, listOf(30L, 10L, 2L), true))
        }
    }

    @Test
    fun `an active owner cannot take focus from another native window or an owned popup`() {
        assertFalse(canRestoreX11DocumentFocus(10, 10, listOf(99L, 2L), true))
        assertFalse(canRestoreX11DocumentFocus(10, 10, listOf(30L, 20L, 2L), true))
        assertFalse(canRestoreX11DocumentFocus(10, 10, null, true))
    }

    @Test
    fun `hidden disposed and special owner handles cannot authorize a document handoff`() {
        assertFalse(canRestoreX11DocumentFocus(10, 10, listOf(30L, 10L, 2L), false))
        for (owner in listOf(0L, 1L)) {
            assertFalse(canRestoreX11DocumentFocus(owner, owner, listOf(owner), true))
        }
    }

    @Test
    fun `PointerRoot and incomplete or cyclic ancestry cannot authorize a document handoff`() {
        val parents = mapOf(30L to 10L, 10L to 2L, 2L to 0L)
        val incomplete = x11FocusAncestors(30, (parents - 2L)::get)
        val cyclic = x11FocusAncestors(30, (parents + (2L to 30L))::get)
        assertFalse(canRestoreX11DocumentFocus(10, 10, incomplete, true))
        assertFalse(canRestoreX11DocumentFocus(10, 10, cyclic, true))
        for (focus in listOf(0L, 1L)) {
            val ancestors = x11FocusAncestors(focus) { error("Special focus has no window ancestry") }
            assertFalse(canRestoreX11DocumentFocus(10, 10, ancestors, true))
        }
    }
}
