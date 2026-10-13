package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbDateFieldUiTest {
    @Test
    fun `the desktop field reports a date only while its text is one and shows the value out of focus`() =
        runSkikoComposeUiTest(size = Size(480f, 240f)) {
            val reported = mutableListOf<String>()
            var value by mutableStateOf("2026-01-01")
            setContent {
                HbTheme {
                    HbColumn {
                        HbDateField(
                            value = value,
                            onValueChange = { reported += it },
                            label = "Due",
                            placeholder = "YYYY-MM-DD",
                            invalidText = "Enter a date as YYYY-MM-DD",
                            cancelText = "Cancel",
                            confirmText = "Choose date",
                        )
                        HbTextField("", {}, accessibleLabel = "Other")
                    }
                }
            }
            val field = onNode(hasSetTextAction() and hasContentDescription("Due"))
            field.assertTextEquals("2026-01-01", includeEditableText = true)

            field.performTextInput("x")
            assertEquals("", reported.last())
            onNodeWithText("Enter a date as YYYY-MM-DD").assertIsDisplayed()
            field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))

            onNode(hasSetTextAction() and hasContentDescription("Other")).performTextInput("y")
            runOnIdle { value = "2026-05-05" }
            field.assertTextEquals("2026-05-05", includeEditableText = true)
        }
}
