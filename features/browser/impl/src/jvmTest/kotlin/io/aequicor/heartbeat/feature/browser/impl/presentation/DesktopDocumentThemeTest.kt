package io.aequicor.heartbeat.feature.browser.impl.presentation

import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbDialogOverlayInsets
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
    fun `modal overlay updates only presentation tokens and resets on returning to the start host`() {
        val start = theme(HbColors.DesktopLight)
        val modal = theme(HbColors.DesktopLight, HbDialogOverlayInsets(44.dp, 40.dp))
        assertEquals("0.0px", start.variables["--hb-overlay-width"])
        assertEquals("0.0px", start.variables["--hb-overlay-height"])
        assertEquals("44.0px", modal.variables["--hb-overlay-width"])
        assertEquals("40.0px", modal.variables["--hb-overlay-height"])
        assertEquals(start, theme(HbColors.DesktopLight))
        assertFalse("__heartbeatData" in desktopDocumentThemeScript(modal))
    }

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
    fun `control states and geometry follow the active host tokens`() {
        val dimensions = HbDimensions.Desktop.copy(
            controlHeight = 40.dp,
            controlCornerRadius = 10.dp,
            fieldCornerRadius = 12.dp,
            fieldFocusWidth = 3.dp,
            focusOutset = 4.dp,
        )
        for (colors in listOf(HbColors.DesktopLight, HbColors.DesktopDark)) {
            val values = colors.browserDocumentTheme(HbTypography.Desktop, HbSpacing(), dimensions, 1f).variables
            assertEquals("40.0px", values["--hb-control-height"])
            assertEquals("10.0px", values["--hb-control-radius"])
            assertEquals("12.0px", values["--hb-field-radius"])
            assertEquals("3.0px", values["--hb-focus-width"])
            assertEquals("4.0px", values["--hb-focus-offset"])
            assertNotEquals(values["--hb-control"], values["--hb-control-hover"])
            assertNotEquals(values["--hb-control-hover"], values["--hb-control-pressed"])
        }
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
    fun `passive fixed-page measurement changes no CSS and grants no owner call bridge`() {
        val page = desktopDocumentHtml(
            "<main>App</main>",
            null,
            null,
            layout = DesktopDocumentLayoutScript("layoutOnly", 8, hasContentStyles = false),
        )
        assertTrue("ResizeObserver" in page)
        assertTrue("const query = null;" in page)
        assertFalse("display:flow-root" in page)
        assertFalse("scrollbar-gutter" in page)
    }

    @Test
    fun `theme values cannot escape the bootstrap script`() {
        val hostile = DesktopDocumentTheme(false, mapOf("--hb-font-family" to "</script>\u2028<!--"))
        val page = desktopDocumentHtml("<p/>", null, null, hostile)
        assertEquals(1, Regex("</script>").findAll(page).count())
        assertFalse("<!--" in page)
        assertFalse('\u2028' in page)
    }

    private fun theme(
        colors: HbColors,
        overlay: HbDialogOverlayInsets = HbDialogOverlayInsets(),
    ): DesktopDocumentTheme = colors.browserDocumentTheme(
        HbTypography.Desktop,
        HbSpacing(),
        HbDimensions.Desktop,
        fontScale = 1.5f,
        overlay = overlay,
    )
}
