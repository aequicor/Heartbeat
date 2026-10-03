package io.aequicor.heartbeat.feature.agentlearning.impl.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningScreenIntent
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningScreenState
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.ApprovalUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.DraftUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.InstructionUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.KindUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.LearningErrorUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.ProjectFilterUi
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.ProjectUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AgentLearningUiTest {
    private val general = InstructionUi("1", KindUi.General, "UTF-8", "", "Use UTF-8", true, "p", null)
    private val skill = InstructionUi("2", KindUi.Skill, "Release", "When releasing", "Steps", false, null, null)
    private val loaded = AgentLearningScreenState(
        isLoaded = true,
        instructions = persistentListOf(general, skill),
        projects = persistentListOf(ProjectUi("p", "Heartbeat")),
    )

    @Test
    fun `instructions are grouped by kind and forward approval, switch and expansion`() =
        runSkikoComposeUiTest(size = Size(900f, 1000f)) {
            val intents = mutableListOf<AgentLearningScreenIntent>()
            val state = mutableStateOf(loaded)
            setContent { HbTheme { AgentLearningContent(state.value, { intents += it }, onBack = null) } }

            onNodeWithTag("agent-learning-kind-General").assertIsDisplayed()
            onNodeWithTag("agent-learning-kind-Skill").assertIsDisplayed()
            onNodeWithTag("agent-learning-enabled-1").assertIsOn()
            onNodeWithTag("agent-learning-enabled-2").assertIsOff()

            onNodeWithTag("agent-learning-approval-Ask").performClick()
            onNodeWithTag("agent-learning-enabled-2").performClick()
            onNodeWithTag("agent-learning-row-1").performClick()
            assertEquals(
                listOf(
                    AgentLearningScreenIntent.SelectApproval(ApprovalUi.Ask),
                    AgentLearningScreenIntent.SetEnabled("2", true),
                    AgentLearningScreenIntent.ToggleExpanded("1"),
                ),
                intents,
            )

            state.value = loaded.copy(expanded = "1")
            onNodeWithTag("agent-learning-details-1").assertIsDisplayed()
            onNodeWithTag("agent-learning-delete-1").performClick()
            assertEquals(AgentLearningScreenIntent.Delete("1"), intents.last())
        }

    @Test
    fun `a refused edit is explained inside the editor and delete asks for confirmation`() =
        runSkikoComposeUiTest(size = Size(900f, 1000f)) {
            val intents = mutableListOf<AgentLearningScreenIntent>()
            val draft = DraftUi("1", KindUi.General, "UTF-8", "", "Use UTF-8", 80, 300, 2_000)
            val state = mutableStateOf(loaded.copy(draft = draft, error = LearningErrorUi.EditRejected))
            setContent { HbTheme { AgentLearningContent(state.value, { intents += it }, onBack = null) } }

            onNodeWithTag("agent-learning-editor-error").assertIsDisplayed()
            onNodeWithTag("agent-learning-error").assertDoesNotExist()
            onNodeWithTag("agent-learning-editor-save").performClick()
            assertEquals(AgentLearningScreenIntent.SaveDraft, intents.last())

            state.value = loaded.copy(draft = draft.copy(content = "x".repeat(2_001)))
            onNodeWithTag("agent-learning-editor-save").assertIsNotEnabled()

            state.value = loaded.copy(deleting = "1")
            onNodeWithTag("agent-learning-delete-confirm").performClick()
            assertEquals(AgentLearningScreenIntent.ConfirmDelete, intents.last())
        }

    @Test
    fun `failed load offers a retry and a hidden filter result offers to show all`() =
        runSkikoComposeUiTest(size = Size(900f, 800f)) {
            val intents = mutableListOf<AgentLearningScreenIntent>()
            val state = mutableStateOf(AgentLearningScreenState(error = LearningErrorUi.LoadFailed))
            setContent { HbTheme { AgentLearningContent(state.value, { intents += it }, onBack = null) } }
            onNodeWithTag("agent-learning-error-action").performClick()
            assertEquals(AgentLearningScreenIntent.Reload, intents.last())

            state.value = loaded.copy(filter = ProjectFilterUi.Project("other"))
            onNodeWithTag("agent-learning-show-all").performClick()
            assertEquals(AgentLearningScreenIntent.SelectFilter(ProjectFilterUi.All), intents.last())
        }

    @Test
    fun `an empty registry explains how the agent learns`() = runSkikoComposeUiTest(size = Size(900f, 800f)) {
        setContent {
            HbTheme { AgentLearningContent(AgentLearningScreenState(isLoaded = true), {}, onBack = null) }
        }
        onNodeWithTag("agent-learning-empty").assertIsDisplayed()
    }
}
