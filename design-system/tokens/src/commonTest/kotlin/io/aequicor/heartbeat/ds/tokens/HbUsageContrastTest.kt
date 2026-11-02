package io.aequicor.heartbeat.ds.tokens

import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbUsageContrastTest {
    @Test
    fun `usage bands remain readable on the pill and popup across all hosts`() {
        listOf(HbColors.Light, HbColors.Dark, HbColors.DesktopLight, HbColors.DesktopDark).forEach { colors ->
            val surfaces = HbSurfaceColors.forHost(
                colors.isDark,
                colors == HbColors.DesktopLight || colors == HbColors.DesktopDark,
            )
            listOf(0, 59, 60, 79, 80, 89, 90, 100).forEach { percent ->
                listOf(
                    surfaces.composerPill,
                    colors.interactionHoverOverlay.compositeOver(surfaces.composerPill),
                    colors.pressedOverlay.compositeOver(surfaces.composerPill),
                    surfaces.composer,
                    colors.surface,
                ).forEach { background ->
                    assertTrue(contrastRatio(colors.usageColor(percent), background) >= 4.5f, "$percent on $background")
                }
            }
            assertEquals(colors.usageLow, colors.usageColor(59))
            assertEquals(colors.usageWarning, colors.usageColor(60))
            assertEquals(colors.usageHigh, colors.usageColor(80))
            assertEquals(colors.usageCritical, colors.usageColor(90))
            assertEquals(colors.textSecondary, colors.usageColor(null))
        }
    }
}
