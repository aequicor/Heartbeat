package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.feature.browser.impl.ui.browserDocumentTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopDocumentThemeTest {
    @Test
    fun `the host palette and scaled typography are reflected without changing app data`() {
        val light = theme(HbColors.DesktopLight)
        val dark = theme(HbColors.DesktopDark)
        assertNotEquals(light.variables["--hb-surface"], dark.variables["--hb-surface"])
        assertNotEquals(light.variables["--hb-text"], dark.variables["--hb-text"])
        assertEquals("19.5px", light.variables["--hb-font-size"])
        assertEquals(JsonPrimitive("light"), light.json()["colorScheme"])
        assertEquals(JsonPrimitive("dark"), dark.json()["colorScheme"])
        val update = desktopDocumentThemeScript(dark)
        assertTrue(update.startsWith("window.__heartbeatTheme &&"))
        assertFalse("__heartbeatData" in update)
        assertFalse("location" in update)
    }

    @Test
    fun `theme and content sizing work without enabling an owner method bridge`() {
        val page = desktopDocumentHtml(
            "<main data-heartbeat-root>App</main>",
            null,
            null,
            theme(HbColors.DesktopDark),
            DesktopDocumentLayoutScript("layoutOnly", 5),
        )
        assertTrue("const query = null;" in page)
        assertTrue("window[\"layoutOnly\"]" in page)
        assertTrue("heartbeat:theme" in page)
        assertTrue("ResizeObserver" in page)
        assertFalse("documentElement.scrollHeight" in page)
        assertTrue("getBoundingClientRect().height" in page)
        val fixed = desktopDocumentHtml("<p/>", null, null)
        assertFalse("ResizeObserver" in fixed)
    }

    @Test
    fun `theme values cannot escape the bootstrap script`() {
        val hostile = DesktopDocumentTheme(false, mapOf("--hb-font-family" to "</script>\u2028<!--"))
        val page = desktopDocumentHtml("<p/>", null, null, hostile)
        assertEquals(1, Regex("</script>").findAll(page).count())
        assertFalse("<!--" in page)
        assertFalse('\u2028' in page)
    }

    private fun theme(colors: HbColors): DesktopDocumentTheme = browserDocumentTheme(
        colors,
        HbTypography.Desktop,
        HbSpacing(),
        HbDimensions.Desktop,
        fontScale = 1.5f,
    )
}
