package io.aequicor.heartbeat.feature.browser.impl.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserDocumentClassLoadingTest {
    @Test
    fun `desktop document composables produce valid JVM classes before a native page is opened`() {
        // Kotlin/Compose can leave an illegal <anonymous> NON_LOCAL_RETURN marker after return@key.
        // JVM verification must happen in the ordinary test suite, even without an available CEF runtime.
        val name = "io.aequicor.heartbeat.feature.browser.impl.ui.BrowserDocumentView_jvmKt"
        val loaded = Class.forName(name, false, javaClass.classLoader)
        assertEquals(name, loaded.name)
    }
}
