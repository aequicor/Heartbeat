package io.aequicor.heartbeat.platform.desktop

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals

class KeychainIntroTest {
    private val directory = Files.createTempDirectory("keychain-intro")
    private var shown = 0

    private fun launch(isMac: Boolean = true, hasLocalSecrets: Boolean = false, version: String? = "1.2.3") =
        showKeychainIntroIfNeeded(isMac, hasLocalSecrets, version, directory) { shown++ }

    @Test
    fun `an installed macOS build shows the introduction once per version`() {
        launch()
        launch()
        assertEquals(1, shown)
        launch(version = "1.2.4")
        assertEquals(2, shown)
        assertEquals(true, directory.resolve("keychain-intro-1.2.3").exists())
    }

    @Test
    fun `other hosts, local secrets and unversioned launches skip it`() {
        launch(isMac = false)
        launch(hasLocalSecrets = true)
        launch(version = null)
        assertEquals(0, shown)
    }
}
