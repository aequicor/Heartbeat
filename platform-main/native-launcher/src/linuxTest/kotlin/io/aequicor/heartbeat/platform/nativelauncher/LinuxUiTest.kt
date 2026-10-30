@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_create
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_dispose
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_get_presentation
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_hide
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_iterate
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_update
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the real GTK ABI and GtkBuilder definition under Xvfb, without network, JVM or user data. */
class LinuxUiTest {
    @Test
    fun nativeWindowSupportsProgressRetryAndHandoffPresentation() {
        if (getenv("DISPLAY")?.toKString().isNullOrBlank()) return
        val window = assertNotNull(hb_gtk_create("/nonexistent-optional-icon.png"))
        try {
            assertEquals(0, hb_gtk_iterate(window))
            hb_gtk_update(window, "Загрузка компонентов…", "500 / 1000 байт", 0.5, 0)
            hb_gtk_iterate(window)
            hb_gtk_get_presentation(window).useContents {
                assertTrue(width > 0)
                assertTrue(height > 0)
            }
            hb_gtk_update(window, "Ошибка сети", " ", -1.0, 1)
            hb_gtk_iterate(window)
            hb_gtk_update(window, "Повтор загрузки…", " ", 0.0, 0)
            hb_gtk_hide(window)
        } finally {
            hb_gtk_dispose(window)
        }
    }
}
