package io.aequicor.heartbeat.platform.shared

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.platform.shared.resources.Res
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_accessibility
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_close
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_instruction
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_tile
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class ComputerUsePermissionGuidePanelTest {
    @Test
    fun `guide identifies the responsible app and offers a keyboard close action`() =
        runSkikoComposeUiTest(size = Size(420f, 300f)) {
            val appName = "IntelliJ IDEA"
            var title = ""
            var instruction = ""
            var tileDescription = ""
            var closeDescription = ""
            var closeCount = 0
            setContent {
                HbTheme {
                    title = stringResource(Res.string.computer_use_guide_accessibility)
                    instruction = stringResource(Res.string.computer_use_guide_instruction, appName)
                    tileDescription = stringResource(Res.string.computer_use_guide_tile, appName)
                    closeDescription = stringResource(Res.string.computer_use_guide_close)
                    ComputerUsePermissionGuidePanel(
                        ComputerUsePermission.Accessibility,
                        appName,
                        null,
                        { closeCount++ },
                    )
                }
            }
            onNodeWithText(title).assertIsDisplayed()
            onNodeWithText(instruction).assertIsDisplayed()
            onNodeWithText(appName, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("computer-use-guide-tile").assertContentDescriptionEquals(tileDescription)
            onNodeWithTag("computer-use-guide").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("computer-use-guide-close")
                .assertContentDescriptionEquals(closeDescription).assertIsFocused()
                .performKeyInput { pressKey(Key.Spacebar) }
            runOnIdle { assertEquals(1, closeCount) }
        }

    @Test
    fun `guide follows the host light and dark themes`() {
        val snapshots = mutableListOf<IntArray>()
        for (isDark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(420f, 300f)) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        HbTheme(darkTheme = isDark) {
                            ComputerUsePermissionGuidePanel(
                                ComputerUsePermission.ScreenRecording,
                                "IntelliJ IDEA",
                                null,
                                {},
                            )
                        }
                    }
                }
                onNodeWithTag("computer-use-guide").assertIsDisplayed()
                val image = captureToImage().toAwtImage()
                snapshots += image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                val file = File("build/reports/snapshots/computer-use-guide-${if (isDark) "dark" else "light"}.png")
                file.parentFile.mkdirs()
                ImageIO.write(image, "png", file)
            }
        }
        assertFalse(snapshots[0].contentEquals(snapshots[1]), "The host theme must change the guide's colors")
    }
}
