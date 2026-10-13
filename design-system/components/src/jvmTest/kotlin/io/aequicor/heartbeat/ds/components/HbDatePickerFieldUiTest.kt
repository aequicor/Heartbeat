package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbDatePickerFieldUiTest {
    @Test
    fun `an empty field stays empty until confirming even an unchanged native date`() =
        runSkikoComposeUiTest(size = Size(480f, 360f)) {
            var value by mutableStateOf("")
            var enabled by mutableStateOf(true)
            val reported = mutableListOf<String>()
            setContent {
                HbTheme {
                    HbDatePickerField(
                        value,
                        {
                            value = it
                            reported += it
                        },
                        HbDateFieldText("Due", "YYYY-MM-DD", "Invalid date", "Cancel", "Choose date"),
                        today = { "2026-10-13" },
                        enabled = enabled,
                    ) { selected, onDate ->
                        HbText(selected)
                        HbButton("Pick tomorrow", { onDate("2026-10-14") })
                    }
                }
            }
            onNodeWithText("YYYY-MM-DD").assertIsDisplayed().performClick()
            onNodeWithText("2026-10-13").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertTrue(reported.isEmpty())
            onNodeWithText("YYYY-MM-DD").assertIsDisplayed().performClick()
            onNodeWithText("Choose date").performClick()
            assertEquals(listOf("2026-10-13"), reported)
            onNodeWithText("2026-10-13").performClick()
            onNodeWithText("Pick tomorrow").performClick()
            onNodeWithText("Choose date").performClick()
            assertEquals(listOf("2026-10-13", "2026-10-14"), reported)

            // Clearing the controlled value must remove the date that the native picker last showed.
            runOnIdle { value = "" }
            onNodeWithText("YYYY-MM-DD").assertIsDisplayed().performClick()
            runOnIdle { value = "2026-11-01" }
            onNodeWithText("Choose date").assertDoesNotExist()
            onNodeWithText("2026-11-01").assertIsDisplayed().performClick()
            runOnIdle { enabled = false }
            onNodeWithText("Choose date").assertDoesNotExist()
            onNodeWithText("2026-11-01").assertIsNotEnabled()
            assertEquals(listOf("2026-10-13", "2026-10-14"), reported)
        }
}
