package io.aequicor.heartbeat.feature.autocontinue.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsScreenState
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.Res
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_more
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_required
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AutoContinueSettingsUiTest {
    @Test
    fun `approved settings and scrollable advanced context render in both themes`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                var more = ""
                var required = ""
                setContent {
                    HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                        more = stringResource(Res.string.auto_more)
                        required = stringResource(Res.string.auto_required)
                        AutoContinueSettingsContent(AutoContinueSettingsScreenState(isLoaded = true), {}, null)
                    }
                }
                waitForIdle()
                val dir = File("build/previews").apply { mkdirs() }
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "settings-$dark.png")))
                onNodeWithText(required).assertDoesNotExist()
                onNodeWithText(more).performClick()
                onNodeWithText(required).assertExists()
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "context-$dark.png")))
            }
        }
    }
}
