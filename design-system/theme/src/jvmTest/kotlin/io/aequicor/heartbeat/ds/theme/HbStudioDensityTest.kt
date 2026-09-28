package io.aequicor.heartbeat.ds.theme

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.tokens.HbStudioDimensions
import io.aequicor.heartbeat.ds.tokens.HbStudioStyle
import io.aequicor.heartbeat.ds.tokens.HbTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbStudioDensityTest {
    @Test
    fun `desktop density remains compact when the Material kit is selected`() = runComposeUiTest {
        var dimensions: HbStudioDimensions? = null
        var typography: HbTypography? = null
        setContent {
            HbTheme(platformUi = PlatformUi.Material) {
                HbStudioTheme {
                    val currentDimensions = HbTheme.studioDimensions
                    val currentTypography = HbTheme.typography
                    SideEffect {
                        dimensions = currentDimensions
                        typography = currentTypography
                    }
                }
            }
        }
        runOnIdle {
            assertTrue(dimensions?.isDesktop == true)
            assertEquals(HbStudioStyle.desktopTypography.body.fontSize, typography?.body?.fontSize)
            assertEquals(HbStudioStyle.desktopTypography.body.lineHeight, typography?.body?.lineHeight)
        }
    }

    @Test
    fun `mobile preview retains its larger studio typography and geometry`() = runComposeUiTest {
        var dimensions: HbStudioDimensions? = null
        var typography: HbTypography? = null
        setContent {
            HbTheme(studioDimensions = HbStudioDimensions.Mobile) {
                HbStudioTheme {
                    val currentDimensions = HbTheme.studioDimensions
                    val currentTypography = HbTheme.typography
                    SideEffect {
                        dimensions = currentDimensions
                        typography = currentTypography
                    }
                }
            }
        }
        runOnIdle {
            assertEquals(HbStudioDimensions.Mobile, dimensions)
            assertEquals(HbStudioStyle.typography.body.fontSize, typography?.body?.fontSize)
            assertEquals(HbStudioStyle.typography.body.lineHeight, typography?.body?.lineHeight)
        }
    }
}
