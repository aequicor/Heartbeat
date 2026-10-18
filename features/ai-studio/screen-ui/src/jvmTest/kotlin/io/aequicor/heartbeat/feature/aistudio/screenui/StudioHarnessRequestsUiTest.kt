package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioHarnessRequestsUiTest {
    @Test
    fun `pending requests open the library and the indicator disappears when answered`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(1280f, 900f)) {
                var opened = 0
                val count = mutableIntStateOf(2)
                setContent {
                    HbTheme(darkTheme = dark) {
                        AiStudioContent(
                            desktopAuditWorkspace(isEmpty = true).copy(harnessAwaitingPermissions = count.intValue),
                            {},
                            StudioExits(onOpenHarnessRequests = { opened++ }),
                        )
                    }
                }
                val directory = File("build/reports/harness-requests").apply { mkdirs() }
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "studio-$dark.png")))
                onNodeWithTag("sidebar-harness-requests").assertIsDisplayed().performClick()
                assertEquals(1, opened)
                runOnIdle { count.intValue = 0 }
                onNodeWithTag("sidebar-harness-requests").assertDoesNotExist()
            }
        }
    }

    @Test
    fun `a host without harness navigation offers no request action`() = runSkikoComposeUiTest {
        setContent {
            HbTheme {
                AiStudioContent(
                    desktopAuditWorkspace(isEmpty = true).copy(harnessAwaitingPermissions = 2),
                    {},
                    StudioExits(),
                )
            }
        }
        onNodeWithTag("sidebar-harness-requests").assertDoesNotExist()
    }

    @Test
    fun `the compact request control stays actionable in both themes`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(420f, 300f)) {
                var opened = 0
                setContent { HbTheme(darkTheme = dark) { HarnessRequestsIndicator(128, { opened++ }) } }
                onNodeWithTag("sidebar-harness-requests").assertIsDisplayed().performClick()
                assertEquals(1, opened)
            }
        }
    }
}
