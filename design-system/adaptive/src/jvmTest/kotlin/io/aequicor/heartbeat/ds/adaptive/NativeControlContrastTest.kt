package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.contrastRatio
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeControlContrastTest {
    @Test
    fun `Material outlined controls use readable text and visible focus instead of pastel fills`() {
        listOf(HbColors.Light, HbColors.Dark).forEach { colors ->
            val controls = materialControlColors(colors)
            assertTrue(contrastRatio(controls.content, colors.surface) >= 4.5f)
            assertTrue(contrastRatio(controls.focusedBorder, colors.surface) >= 3f)
            assertTrue(contrastRatio(controls.cursor, colors.surface) >= 3f)
        }
    }

    @Test
    fun `Fluent button and input overrides meet AA in light and dark`() {
        listOf(HbColors.Light, HbColors.Dark).forEach { colors ->
            listOf(true, false).forEach { primary ->
                val button = fluentButtonColor(colors, primary)
                assertTrue(contrastRatio(button.contentColor, button.fillColor) >= 4.5f)
            }
            val field = fluentTextFieldColor(colors)
            assertTrue(contrastRatio(field.contentColor, field.fillColor) >= 4.5f)
            assertTrue(contrastRatio(field.placeholderColor, field.fillColor) >= 4.5f)
            assertTrue(contrastRatio(field.placeholderColor, colors.surfaceElevated) >= 4.5f)
        }
    }

    @Test
    fun `macOS accent label meets AA throughout native hover and pressed overlays`() {
        listOf(HbColors.Light, HbColors.Dark).forEach { colors ->
            listOf(Color.Transparent, colors.hoverOverlay, colors.pressedOverlay).forEach { target ->
                for (step in 0..10) {
                    val overlay = target.copy(alpha = target.alpha * step / 10)
                    val background = overlay.compositeOver(colors.brand)
                    assertTrue(contrastRatio(macOsAccentContentColor(colors, overlay), background) >= 4.5f)
                }
            }
        }
    }
}
