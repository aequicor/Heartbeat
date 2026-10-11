package io.aequicor.heartbeat.feature.browser.impl.data

import me.friwi.jcefmaven.EnumOS
import org.cef.CefSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBrowserRuntimePathsTest {
    @Test
    fun `windows helper and resources use the bundled runtime rather than SDK discovery`() {
        val directory = File("build/browser-runtime-test/pinned-version")
        val settings = CefSettings()

        settings.useBundledBrowserPaths(directory, EnumOS.WINDOWS)

        assertEquals(File(directory, "jcef_helper.exe").absolutePath, settings.browser_subprocess_path)
        assertEquals(directory.absolutePath, settings.resources_dir_path)
        assertEquals(File(directory, "locales").absolutePath, settings.locales_dir_path)
    }

    @Test
    fun `macOS keeps the framework paths supplied by jcefmaven`() {
        val settings = CefSettings().apply {
            browser_subprocess_path = "/bundle/jcef Helper.app/Contents/MacOS/jcef Helper"
            resources_dir_path = "/bundle/Chromium Embedded Framework.framework/Resources"
            locales_dir_path = "/bundle/Chromium Embedded Framework.framework/Resources/locales"
        }

        settings.useBundledBrowserPaths(File("another-runtime"), EnumOS.MACOSX)

        assertEquals("/bundle/jcef Helper.app/Contents/MacOS/jcef Helper", settings.browser_subprocess_path)
        assertEquals("/bundle/Chromium Embedded Framework.framework/Resources", settings.resources_dir_path)
        assertEquals("/bundle/Chromium Embedded Framework.framework/Resources/locales", settings.locales_dir_path)
    }
}
