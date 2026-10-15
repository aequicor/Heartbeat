package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationPhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudioEnginePreparationUiTest {
    @Test
    fun `first use progress and retry guidance stay below the window header at compact and desktop widths`() {
        for (width in listOf(420, 1280)) {
            for (dark in listOf(false, true)) {
                for (phase in listOf(EnginePreparationPhaseUi.Downloading, EnginePreparationPhaseUi.Network)) {
                    render(width, dark, phase)
                }
            }
        }
    }

    private fun render(width: Int, dark: Boolean, phase: EnginePreparationPhaseUi) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            val state = desktopAuditWorkspace(isEmpty = false).copy(
                enginePreparations = persistentListOf(EnginePreparationUi("Pi", phase, 42)),
            )
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                        AiStudioContent(state, {}, StudioExits(onBack = {}, onOpenToggles = {}))
                    }
                }
            }
            onNodeWithTag("studio-engine-preparation").assertIsDisplayed()
            val status = onNodeWithTag("studio-engine-preparation").fetchSemanticsNode().boundsInRoot
            assertTrue(status.top >= 44f, "Preparation must not displace the native window header")
            onNodeWithTag("pane-menu-0").assertIsDisplayed()
            val file = File("build/reports/engine-preparation/studio-$width-$dark-$phase.png")
            file.parentFile.mkdirs()
            check(ImageIO.write(captureToImage().toAwtImage(), "png", file))
        }
}
