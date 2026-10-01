package io.aequicor.heartbeat.feature.attachments.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentScreenState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AttachmentDialogTest {
    @Test
    fun `Escape dismisses the attachment dialog while the studio remains composed underneath`() =
        runSkikoComposeUiTest(size = Size(800f, 600f)) {
            var isOpen by mutableStateOf(true)
            setContent {
                HbTheme {
                    HbText("Studio workspace")
                    if (isOpen) {
                        AttachmentScreen(
                            AttachmentScreenState(name = "report.txt", text = "User material", isLoading = false),
                            {},
                            { isOpen = false },
                        )
                    }
                }
            }
            onNodeWithText("report.txt").assertIsDisplayed().performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("report.txt").assertDoesNotExist()
            onNodeWithText("Studio workspace").assertIsDisplayed()
        }
}
