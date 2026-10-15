package io.aequicor.heartbeat.platform.desktop

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTrayTest {
    @Test
    fun `tray actions are localized in English and Russian`() {
        assertEquals(DesktopTrayLabels("Open Heartbeat", "Quit Heartbeat"), desktopTrayLabels(Locale.ROOT))
        assertEquals(
            DesktopTrayLabels("Открыть Heartbeat", "Выйти из Heartbeat"),
            desktopTrayLabels(Locale.forLanguageTag("ru-RU")),
        )
    }
}
