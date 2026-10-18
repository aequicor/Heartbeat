package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailState
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RequestChoiceUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RequestKindUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RequestOptionUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RunRequestUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RunStatusUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RunUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HarnessRunRequestsUiTest {
    private val buttons = request(kind = RequestKindUi.Buttons)

    @Test
    fun `a cell request is shown in its run and its option answers it`() = runSkikoComposeUiTest(Size(900f, 800f)) {
        val intents = mutableListOf<HarnessDetailIntent>()
        setContent { HbTheme { HarnessDetailContent(state(buttons), intents::add, {}) } }

        onNodeWithTag("harness-run-request-${buttons.key}").assertExists()
        onNodeWithText("Run a command?").assertExists()
        onNodeWithTag("harness-run-request-option-${buttons.key}-allow").performClick()
        runOnIdle {
            assertEquals(listOf<HarnessDetailIntent>(HarnessDetailIntent.DecideRequest(buttons, "allow")), intents)
        }
    }

    @Test
    fun `a choice enables its answer while a skip is always available`() = runSkikoComposeUiTest(Size(900f, 800f)) {
        val intents = mutableListOf<HarnessDetailIntent>()
        val choice = request(
            kind = RequestKindUi.SingleChoice,
            choices = listOf(RequestChoiceUi("a", "Option A"), RequestChoiceUi("b", "Option B")),
            min = 1,
            max = 1,
        )
        setContent { HbTheme { HarnessDetailContent(state(choice), intents::add, {}) } }

        onNodeWithTag("harness-run-request-option-${choice.key}-allow").assertIsNotEnabled()
        onNodeWithTag("harness-run-request-option-${choice.key}-skip").assertIsEnabled()
        onNodeWithTag("harness-run-request-choice-${choice.key}-b").performClick()
        onNodeWithTag("harness-run-request-option-${choice.key}-allow").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(
                listOf<HarnessDetailIntent>(HarnessDetailIntent.DecideRequest(choice, "allow", setOf("b"))),
                intents,
            )
        }
    }

    @Test
    fun `a typed answer is sent with the option the user chose`() = runSkikoComposeUiTest(Size(900f, 800f)) {
        val intents = mutableListOf<HarnessDetailIntent>()
        val text = request(kind = RequestKindUi.FreeText, placeholder = "Why?")
        setContent { HbTheme { HarnessDetailContent(state(text), intents::add, {}) } }

        onNodeWithTag("harness-run-request-text-${text.key}").performTextInput("because")
        onNodeWithTag("harness-run-request-option-${text.key}-allow").performClick()
        runOnIdle {
            assertEquals(
                listOf<HarnessDetailIntent>(HarnessDetailIntent.DecideRequest(text, "allow", emptySet(), "because")),
                intents,
            )
        }
    }

    @Test
    fun `a run without requests shows no request card`() = runSkikoComposeUiTest(Size(900f, 800f)) {
        setContent { HbTheme { HarnessDetailContent(state(), {}, {}) } }
        onNodeWithTag("harness-run-wf_one").assertExists()
        onNodeWithTag("harness-run-request-${buttons.key}").assertDoesNotExist()
    }

    private fun state(vararg requests: RunRequestUi) = HarnessDetailState(
        phase = PhaseUi.Ready,
        name = "compose_ui",
        title = "Compose UI",
        isEnabled = true,
        runs = persistentListOf(
            RunUi("wf_one", "Ship", RunStatusUi.Running, 1, requests.size, persistentListOf(*requests)),
        ),
    )

    private fun request(
        kind: RequestKindUi,
        choices: List<RequestChoiceUi> = emptyList(),
        min: Int = 0,
        max: Int = 0,
        placeholder: String? = null,
    ) = RunRequestUi(
        run = "wf_one",
        step = "s0",
        cell = "c1",
        cellName = "Builder",
        turn = "turn",
        request = "req",
        title = "Run a command?",
        description = null,
        options = persistentListOf(
            RequestOptionUi("allow", "Allow", isSkip = false),
            RequestOptionUi("skip", "Skip", isSkip = true),
        ),
        kind = kind,
        choices = persistentListOf(*choices.toTypedArray()),
        min = min,
        max = max,
        placeholder = placeholder,
    )
}
