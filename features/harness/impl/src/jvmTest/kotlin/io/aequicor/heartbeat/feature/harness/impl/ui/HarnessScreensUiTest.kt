package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
}
