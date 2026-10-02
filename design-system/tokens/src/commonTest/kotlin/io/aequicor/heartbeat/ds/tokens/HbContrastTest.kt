package io.aequicor.heartbeat.ds.tokens

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbContrastTest {
    @Test
    fun `agent pointer outline contrasts with its fill in both desktop themes`() {
        listOf(HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            assertTrue(
                contrastRatio(colors.computerUsePointerOutline, colors.computerUsePointer) >= 4.5f,
                "Agent pointer outline fails contrast (dark=${colors.isDark})",
            )
        }
    }

    @Test
    fun `console roles remain readable on opaque dark surfaces in both themes`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            assertEquals(1f, colors.consoleSurface.alpha)
            assertTrue(colors.consoleSurface.luminance() < colors.surface.luminance())
            val roles = mapOf(
                "text" to colors.consoleText,
                "muted" to colors.consoleMuted,
                "command" to colors.consoleCommand,
                "info" to colors.consoleInfo,
                "success" to colors.consoleSuccess,
                "warning" to colors.consoleWarning,
                "error" to colors.consoleError,
            )
            roles.forEach { (role, foreground) ->
                val contrast = contrastRatio(foreground, colors.consoleSurface)
                assertTrue(
                    contrast >= 4.5f,
                    "Console $role contrast $contrast fails AA (dark=${colors.isDark})",
                )
            }
        }
        assertTrue(HbColors.Light.consoleSurface.luminance() > HbColors.Dark.consoleSurface.luminance())
    }

    @Test
    fun `syntax text meets WCAG AA on code and message surfaces in both themes`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            val syntax = mapOf(
                "keyword" to colors.syntaxKeyword,
                "string" to colors.syntaxString,
                "number" to colors.syntaxNumber,
                "comment" to colors.syntaxComment,
                "type" to colors.syntaxType,
                "function" to colors.syntaxFunction,
                "annotation" to colors.syntaxAnnotation,
            )
            val surfaces = mapOf(
                "code" to colors.surfaceElevated,
                "assistant" to colors.assistantSurface,
                "surface" to colors.surface,
                "brand tone" to colors.accentMuted,
                "success tone" to colors.successContainer,
                "warning tone" to colors.warningContainer,
                "danger tone" to colors.errorContainer,
            )
            syntax.forEach { (role, foreground) ->
                surfaces.forEach { (surface, background) ->
                    val contrast = contrastRatio(foreground, background)
                    assertTrue(
                        contrast >= 4.5f,
                        "Syntax $role on $surface contrast $contrast fails AA (dark=${colors.isDark})",
                    )
                }
            }
        }
    }

    @Test
    fun `semantic text meets WCAG AA in both themes`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            val pairs = listOf(
                colors.textPrimary to colors.background,
                colors.textPrimary to colors.surface,
                colors.textSecondary to colors.surface,
                colors.textSecondary to colors.surfaceElevated,
                colors.textPrimary to colors.assistantSurface,
                colors.textSecondary to colors.assistantSurface,
                colors.onPrimary to colors.primary,
                colors.onBrand to colors.brand,
                colors.onSecondary to colors.secondary,
                colors.onError to colors.error,
                colors.onSuccess to colors.success,
                colors.onWarning to colors.warning,
                colors.textPrimary to colors.primaryContainer,
                colors.textPrimary to colors.secondaryContainer,
                colors.textPrimary to colors.errorContainer,
                colors.textPrimary to colors.successContainer,
                colors.textPrimary to colors.warningContainer,
                colors.textPrimary to colors.infoContainer,
                colors.onAccentMuted to colors.accentMuted,
                colors.textPrimary to colors.buttonFill,
                colors.textPrimary to colors.inputFill,
                colors.textSecondary to colors.inputFill,
            )
            pairs.forEach { (foreground, background) ->
                val contrast = contrastRatio(foreground, background)
                assertTrue(contrast >= 4.5f, "Contrast $contrast fails AA (dark=${colors.isDark})")
            }
        }
    }

    @Test
    fun `interactive outline remains distinguishable from surfaces`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            assertTrue(contrastRatio(colors.outline, colors.surface) >= 3f)
        }
    }

    @Test
    fun `soft buttons preserve readable labels while hovered and pressed in both themes`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            val overlays = mapOf(
                "hover" to colors.interactionHoverOverlay,
                "pressed" to colors.pressedOverlay,
            )
            overlays.forEach { (state, overlay) ->
                val primaryContrast = contrastRatio(colors.onPrimary, overlay.compositeOver(colors.primary))
                val neutralContrast = contrastRatio(colors.textPrimary, overlay.compositeOver(colors.buttonFill))
                assertTrue(
                    primaryContrast >= 4.5f,
                    "Primary $state contrast $primaryContrast fails AA (dark=${colors.isDark})",
                )
                assertTrue(
                    neutralContrast >= 4.5f,
                    "Secondary/Quiet $state contrast $neutralContrast fails AA (dark=${colors.isDark})",
                )
            }
        }
    }
}
