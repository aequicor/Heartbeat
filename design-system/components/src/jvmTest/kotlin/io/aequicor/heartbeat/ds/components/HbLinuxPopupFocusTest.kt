package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HbLinuxPopupFocusTest {
    @Test
    fun `a native child of the active mapped owner permits the popup handoff`() {
        assertTrue(canRestoreX11PopupFocus(10, 20, 10, listOf(30L, 10L, 2L), true))
    }

    @Test
    fun `native ownership alone cannot activate a background application`() {
        for (active in listOf(0L, 1L, 20L, 99L)) {
            assertFalse(canRestoreX11PopupFocus(10, 20, active, listOf(30L, 10L, 2L), true))
        }
    }

    @Test
    fun `an active owner cannot take foreign native focus or focus an unmapped popup`() {
        assertFalse(canRestoreX11PopupFocus(10, 20, 10, listOf(99L, 2L), true))
        assertFalse(canRestoreX11PopupFocus(10, 20, 10, null, true))
        assertFalse(canRestoreX11PopupFocus(10, 20, 10, listOf(30L, 10L, 2L), false))
    }

    @Test
    fun `None PointerRoot and the owner itself are invalid popup handles`() {
        for (popup in listOf(0L, 1L, 10L)) {
            assertFalse(canRestoreX11PopupFocus(10, popup, 10, listOf(10L, 2L), true))
        }
        for (owner in listOf(0L, 1L)) {
            assertFalse(canRestoreX11PopupFocus(owner, 20, owner, listOf(owner), true))
        }
    }

    @Test
    fun `nested native content is authorized only by its known AWT owner chain`() {
        val nativeOwners = setOf(10L, 40L)
        assertTrue(canRestoreX11PopupFocus(10, 20, 10, listOf(30L, 40L, 2L), true, nativeOwners))
        assertFalse(canRestoreX11PopupFocus(10, 20, 10, listOf(30L, 50L, 2L), true, nativeOwners))
        assertFalse(canRestoreX11PopupFocus(10, 20, 99, listOf(30L, 40L, 2L), true, nativeOwners))
        assertFalse(canRestoreX11PopupFocus(10, 20, 10, listOf(30L, 20L, 2L), true, nativeOwners + 20L))
        assertFalse(canRestoreX11PopupFocus(10, 20, 10, listOf(30L, 40L, 2L), true, setOf(40L)))
    }

    @Test
    fun `the focus walk must reach the root even after encountering the owner`() {
        val parents = mapOf(30L to 10L, 10L to 2L, 2L to 0L)
        assertEquals(listOf(30L, 10L, 2L), x11FocusAncestors(30, parents::get))
        assertNull(x11FocusAncestors(30, (parents - 2L)::get))
        assertNull(x11FocusAncestors(30, (parents + (2L to 30L))::get))
        assertNull(x11FocusAncestors(30) { it + 1 })
    }

    @Test
    fun `special focus values never query a window`() {
        for (focus in listOf(0L, 1L)) {
            assertNull(x11FocusAncestors(focus) { error("None and PointerRoot are not window handles") })
        }
    }
}
