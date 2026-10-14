package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxBrowserLocationsTest {
    private val home = Path.of("user-home").toAbsolutePath()

    @Test
    fun `linux browser manifests live in per-user configuration directories`() {
        val browsers = BrowserLocations.linux(home, null).associateBy { it.browser }
        assertEquals(ChromiumBrowser.entries.toSet(), browsers.keys)
        assertEquals(home.resolve(".config/google-chrome"), browsers.getValue(ChromiumBrowser.Chrome).userData)
        assertEquals(home.resolve(".config/chromium"), browsers.getValue(ChromiumBrowser.Chromium).userData)
        browsers.values.forEach { location ->
            assertTrue(location is BrowserLocation.Directory)
            assertEquals(location.userData.resolve("NativeMessagingHosts"), location.manifest.parent)
        }
    }

    @Test
    fun `absolute XDG override is honored and relative or empty values are ignored`() {
        val config = home.resolve("custom config")
        assertTrue(BrowserLocations.linux(home, config.toString()).all { it.userData.startsWith(config) })
        for (invalid in listOf("", "relative/config")) {
            assertEquals(BrowserLocations.linux(home, null), BrowserLocations.linux(home, invalid))
        }
    }
}
