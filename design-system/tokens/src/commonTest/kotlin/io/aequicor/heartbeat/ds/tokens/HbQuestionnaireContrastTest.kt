package io.aequicor.heartbeat.ds.tokens

import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbQuestionnaireContrastTest {
    @Test
    fun `questionnaire text and controls remain readable in every host theme and interaction state`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { host ->
            val colors = host.forQuestionnaire()
            assertTrue(colors.isDark)
            assertEquals(1f, colors.background.alpha)
            assertEquals(1f, colors.surface.alpha)
            val states = mapOf(
                "rest" to null,
                "hover" to colors.interactionHoverOverlay,
                "pressed" to colors.pressedOverlay,
            )
            val surfaces = mapOf(
                "surface and chips" to colors.surface,
                "input" to colors.inputFill,
                "secondary button" to colors.buttonFill,
            )
            states.forEach { (state, overlay) ->
                surfaces.forEach { (role, base) ->
                    val background = overlay?.compositeOver(base) ?: base
                    listOf(colors.textPrimary, colors.textSecondary).forEach { foreground ->
                        val contrast = contrastRatio(foreground, background)
                        assertTrue(
                            contrast >= 4.5f,
                            "Questionnaire $role $state contrast $contrast fails AA (hostDark=${host.isDark})",
                        )
                    }
                }
                val primary = overlay?.compositeOver(colors.primary) ?: colors.primary
                val primaryContrast = contrastRatio(colors.onPrimary, primary)
                assertTrue(primaryContrast >= 4.5f, "Questionnaire primary $state contrast $primaryContrast fails AA")
                val selected = overlay?.compositeOver(colors.selectedContainer) ?: colors.selectedContainer
                val selectedContrast = contrastRatio(colors.textPrimary, selected)
                assertTrue(
                    selectedContrast >= 4.5f,
                    "Questionnaire selected $state contrast $selectedContrast fails AA",
                )
            }
            assertTrue(contrastRatio(colors.onSecondary, colors.secondary) >= 4.5f)
            val focus = colors.focusRing.compositeOver(colors.surface)
            assertTrue(contrastRatio(focus, colors.surface) >= 3f)
        }
    }
}
