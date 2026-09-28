package io.aequicor.heartbeat.ds.theme

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbShapes
import io.aequicor.heartbeat.ds.tokens.HbSurfaceColors
import io.aequicor.heartbeat.ds.tokens.HbTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbHostPresetTest {
    private class Snapshot(
        val dimensions: HbDimensions,
        val typography: HbTypography,
        val colors: HbColors,
        val surfaces: HbSurfaceColors,
        val shapes: HbShapes,
    )

    @Test
    fun `desktop host selects the dense preset of the single token set whatever kit is selected`() = runComposeUiTest {
        var snapshot: Snapshot? = null
        setContent {
            HbTheme(darkTheme = false, platformUi = PlatformUi.Material) {
                val current = Snapshot(
                    HbTheme.dimensions,
                    HbTheme.typography,
                    HbTheme.colors,
                    HbTheme.surfaces,
                    HbTheme.shapes,
                )
                SideEffect { snapshot = current }
            }
        }
        runOnIdle {
            val value = checkNotNull(snapshot)
            assertTrue(value.dimensions.isDesktop)
            assertEquals(HbDimensions.Desktop.navigationRowHeight, value.dimensions.navigationRowHeight)
            assertEquals(HbTypography.Desktop.body.fontSize, value.typography.body.fontSize)
            assertEquals(HbColors.DesktopLight, value.colors)
            assertEquals(HbSurfaceColors.DesktopLight, value.surfaces)
            assertEquals(HbShapes.Desktop, value.shapes)
        }
    }

    @Test
    fun `mobile preset keeps touch targets and reading typography`() = runComposeUiTest {
        var snapshot: Snapshot? = null
        setContent {
            HbTheme(darkTheme = true, dimensions = HbDimensions.Mobile) {
                val current = Snapshot(
                    HbTheme.dimensions,
                    HbTheme.typography,
                    HbTheme.colors,
                    HbTheme.surfaces,
                    HbTheme.shapes,
                )
                SideEffect { snapshot = current }
            }
        }
        runOnIdle {
            val value = checkNotNull(snapshot)
            assertEquals(HbDimensions.Mobile, value.dimensions)
            assertTrue(value.dimensions.touchTarget.value >= 44f)
            assertEquals(HbTypography.Mobile.body.lineHeight, value.typography.body.lineHeight)
            assertEquals(HbColors.Dark, value.colors)
            assertEquals(HbSurfaceColors.Dark, value.surfaces)
        }
    }

    @Test
    fun `no preset renders text below twelve sp`() {
        for (typography in listOf(HbTypography.Mobile, HbTypography.Desktop)) {
            listOf(typography.caption, typography.metadata, typography.label, typography.body).forEach {
                assertTrue(it.fontSize.value >= 12f, "$it")
            }
        }
    }
}
