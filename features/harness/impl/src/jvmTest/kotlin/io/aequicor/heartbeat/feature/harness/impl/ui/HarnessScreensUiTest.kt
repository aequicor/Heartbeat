package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.ApprovalUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.DiagnosticUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemState
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryState
import io.aequicor.heartbeat.feature.harness.impl.presentation.ItemKindUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HarnessScreensUiTest {
    @Test
    fun `empty library offers approval levels as exclusive choices`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val intents = mutableListOf<HarnessLibraryIntent>()
        setContent {
            HbTheme {
                HarnessLibraryContent(
                    HarnessLibraryState(phase = PhaseUi.Ready, approval = ApprovalUi.Ask),
                    intents::add,
                    null,
                )
            }
        }
        onNodeWithTag("harness-library-empty").assertExists()
        onNodeWithTag("harness-approval-Ask").assertIsSelected()
        onNodeWithTag("harness-approval-AcceptAll").performClick()
        runOnIdle {
            assertEquals(
                listOf<HarnessLibraryIntent>(HarnessLibraryIntent.SelectApproval(ApprovalUi.AcceptAll)),
                intents,
            )
        }
    }

    @Test
    fun `failing code lists its diagnostics and an unchanged item cannot be saved`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val intents = mutableListOf<HarnessItemIntent>()
            val state = HarnessItemState(
                phase = PhaseUi.Ready,
                harnessName = "compose_ui",
                name = "verify",
                kind = ItemKindUi.Script,
                text = "hooks.beforeTool { call ->\n    Continue\n}",
                diagnostics = persistentListOf(DiagnosticUi(2, 5, "Unresolved reference", isError = true)),
            )
            setContent { HbTheme { HarnessItemContent(state, intents::add, {}) } }

            onNodeWithTag("harness-item-diagnostics").assertExists()
            onNodeWithTag("harness-item-editor").assertExists()
            onNodeWithTag("harness-item-save").assertIsNotEnabled()
            runOnIdle { assertEquals(emptyList(), intents) }
        }

    @Test
    fun `a restored draft cannot be discarded while it is being saved`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val intents = mutableListOf<HarnessItemIntent>()
            val state = HarnessItemState(
                phase = PhaseUi.Ready,
                harnessName = "compose_ui",
                name = "rule",
                text = "Use only tokens",
                isDirty = true,
                isDraftRestored = true,
                isSaving = true,
            )
            setContent { HbTheme { HarnessItemContent(state, intents::add, {}) } }

            onNodeWithTag("harness-item-draft-discard").assertIsNotEnabled()
            runOnIdle { assertEquals(emptyList(), intents) }
        }

    @Test
    fun `cancel and Escape close the conflict question without reloading or overwriting`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val intents = mutableListOf<HarnessItemIntent>()
            val state = HarnessItemState(
                phase = PhaseUi.Ready,
                harnessName = "compose_ui",
                name = "rule",
                text = "Use only tokens",
                isDirty = true,
                isConflict = true,
            )
            setContent { HbTheme { HarnessItemContent(state, intents::add, {}) } }

            onNodeWithTag("harness-item-conflict-cancel").performClick()
            onNodeWithTag("harness-item-conflict").performKeyInput { pressKey(Key.Escape) }
            runOnIdle {
                assertEquals<List<HarnessItemIntent>>(
                    listOf(HarnessItemIntent.DismissConflict, HarnessItemIntent.DismissConflict),
                    intents,
                )
            }
        }

    @Test
    fun `editor keys are announced only for an editable item`() = runSkikoComposeUiTest(size = Size(900f, 900f)) {
        val keys = hasContentDescription("Ctrl+Tab", substring = true)
        var state by mutableStateOf(
            HarnessItemState(phase = PhaseUi.Ready, harnessName = "compose_ui", name = "rule", text = "Use tokens"),
        )
        setContent { HbTheme { HarnessItemContent(state, {}, {}) } }

        onAllNodes(keys).assertCountEquals(1)
        state = state.copy(isReadOnly = true)
        waitForIdle()
        onAllNodes(keys).assertCountEquals(0)
    }
}
