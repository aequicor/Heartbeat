package io.aequicor.heartbeat.platform.desktop

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class KeychainIntroTest {
    private val directory = Files.createTempDirectory("keychain-intro")
    private var shown = 0

    private fun launch(isMac: Boolean = true, hasLocalSecrets: Boolean = false, version: String? = "1.2.3") =
        showKeychainIntroIfNeeded(isMac, hasLocalSecrets, version, directory) { shown++ }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `an installed macOS build shows the introduction once per version`() {
        launch()
        launch()
        assertEquals(1, shown)
        launch(version = "1.2.4")
        assertEquals(2, shown)
        assertTrue(directory.resolve("keychain-intro-1.2.3").exists())
    }

    @Test
    fun `other hosts, local secrets and unversioned launches skip it`() {
        launch(isMac = false)
        launch(hasLocalSecrets = true)
        launch(version = null)
        assertEquals(0, shown)
    }

    @Test
    fun `a window that cannot be shown does not stop the launch and leaves no marker`() {
        showKeychainIntroIfNeeded(true, false, "1.2.3", directory) { error("headless") }

        assertEquals(false, directory.resolve("keychain-intro-1.2.3").exists())
    }

    @Test
    fun `an unexpected version is skipped`() {
        launch(version = "../x")
        assertEquals(0, shown)
    }
}
