package io.aequicor.heartbeat.ds.tokens

import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbSurfaceContrastTest {
    @Test
    fun `opaque window surfaces keep readable text in every host preset`() {
        val palettes = listOf(
            HbSurfaceColors.Light to HbColors.Light,
            HbSurfaceColors.Dark to HbColors.Dark,
            HbSurfaceColors.DesktopLight to HbColors.DesktopLight,
            HbSurfaceColors.DesktopDark to HbColors.DesktopDark,
        )
        palettes.forEach { (studio, colors) ->
            assertEquals(1f, studio.sidebar.alpha, "Window surfaces are opaque")
            val backdrops = listOf(studio.backdrop)
            val surfaces = mapOf(
                "rail" to studio.rail,
                "sidebar" to studio.sidebar,
                "header" to studio.header,
                "outgoing" to studio.outgoing,
                "avatar" to studio.avatar,
                "assistant" to studio.assistant,
                "composer" to studio.composer,
                "tool" to studio.tool,
                "console" to studio.console,
                "composer pill" to studio.composerPill,
            )
            backdrops.forEach { backdrop ->
                assertTrue(contrastRatio(colors.textPrimary, backdrop) >= 4.5f)
                surfaces.forEach { (role, surface) ->
                    val background = surface.compositeOver(backdrop)
                    assertTrue(contrastRatio(colors.textPrimary, background) >= 4.5f)
                    assertTrue(
                        contrastRatio(colors.textSecondary, background) >= 4.5f,
                        "Studio $role secondary text fails AA (dark=${colors.isDark})",
                    )
                }
            }
            val selectedStates = listOf(
                studio.selected,
                colors.interactionHoverOverlay.compositeOver(studio.selected),
                colors.pressedOverlay.compositeOver(studio.selected),
            )
            selectedStates.forEach { selected ->
                assertTrue(contrastRatio(studio.onSelected, selected) >= 4.5f)
            }
        }
    }

    @Test
    fun `agent reading surfaces are opaque and tool statuses and composer controls pass AA`() {
        val palettes = listOf(
            HbSurfaceColors.Light,
            HbSurfaceColors.Dark,
            HbSurfaceColors.DesktopLight,
            HbSurfaceColors.DesktopDark,
        )
        palettes.forEach { studio ->
            assertEquals(1f, studio.assistant.alpha, "Agent reading surface must stay opaque")
            listOf(studio.assistant, studio.tool, studio.console).forEach { surface ->
                assertTrue(contrastRatio(studio.accent, surface) >= 4.5f, "Lavender foreground fails AA")
                assertTrue(contrastRatio(studio.success, surface) >= 4.5f, "Tool success foreground fails AA")
            }
            assertTrue(contrastRatio(studio.onComposerAction, studio.composerAction) >= 4.5f)
            assertTrue(contrastRatio(studio.onComposerPillAccent, studio.composerPillAccent) >= 4.5f)
        }
    }

    @Test
    fun `desktop focus rings remain visible against neutral controls and selected navigation`() {
        val palettes = listOf(
            HbSurfaceColors.DesktopLight to HbColors.DesktopLight,
            HbSurfaceColors.DesktopDark to HbColors.DesktopDark,
        )
        palettes.forEach { (studio, colors) ->
            listOf(studio.sidebar, studio.selected, studio.composer, colors.inputFill).forEach { surface ->
                val ring = colors.focusRing.compositeOver(surface)
                assertTrue(contrastRatio(ring, surface) >= 3f, "Desktop focus ring fails non-text AA")
            }
        }
    }
}
