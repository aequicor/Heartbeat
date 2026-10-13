package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.chromiumplugin.panel.store.RemoteStudioState
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelIntent
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelState
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioViewStatus
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioPanelNoticeUiTest {
    @Test
    fun `recovery notices fit a narrow panel and keep the stale studio read only`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val inputs = mutableListOf<StudioPanelIntent>()
            val state = StudioPanelState(
                remote = RemoteStudioState(status = StudioViewStatus.TooLarge, screen = AiStudioScreenState()),
                capturedPage = WirePage(url = "https://example.com", title = "Example", text = "Page"),
                recoveryDraft = "Previous draft",
            )
            setContent {
                HbTheme {
                    StudioPanelContent(state, inputs::add, Modifier.background(HbTheme.surfaces.backdrop))
                }
            }
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            onNodeWithTag("studio-read-only").assertExists()
            onNodeWithTag("studio-new-view").assertIsDisplayed().performClick()
            assertEquals(listOf<StudioPanelIntent>(StudioPanelIntent.NewView), inputs)
            check(ImageIO.write(captureToImage().toAwtImage(), "png", File("/tmp/pr330-panel-notices.png")))
        }
}
