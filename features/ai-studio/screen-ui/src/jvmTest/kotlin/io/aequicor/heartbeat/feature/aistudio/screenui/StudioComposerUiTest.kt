package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_auto
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_edits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_safety
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_effort_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_default
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_low
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_menu_section
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_remember
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EffortUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioComposerUiTest {
    @Test
    fun `native composer uses compact name and only advertised reasoning options`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val pane = PaneUi(0)
            val model = ModelUi(
                "native-route",
                "Engine · Model · My connection",
                shortName = "Model",
                reasoningEfforts = persistentListOf("future-effort"),
            )
            val initial = AiStudioScreenState(panes = persistentListOf(pane))
            val state = initial.copy(
                models = persistentListOf(model),
                settings = initial.settings.copy(modelId = model.id),
            )
            var lowLabel = ""
            setContent {
                lowLabel = stringResource(Res.string.effort_low)
                NativeComposerFixture(state.paneContent(pane), events::add)
            }
            onNodeWithText(model.shortName).assertIsDisplayed()
            onNodeWithContentDescription(model.name).assertIsDisplayed()
            onNodeWithTag("effort-chip").performClick()
            onNodeWithText(lowLabel).assertDoesNotExist()
            onNodeWithText("future-effort").performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.SelectEngineEffort(model.id, "future-effort", 0)),
                    events,
                )
            }
        }

    @Test
    fun `panel exposes working demo preferences and prompt templates`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val pane = PaneUi(0)
            val state = AiStudioScreenState(panes = persistentListOf(pane), models = StudioModelOptions)
            var autoLabel = ""
            var lowLabel = ""
            var effortLabel = ""
            var addLabel = ""
            var planLabel = ""
            var planPrompt = ""
            setContent {
                autoLabel = stringResource(Res.string.approval_auto)
                lowLabel = stringResource(Res.string.effort_low)
                effortLabel = stringResource(Res.string.composer_effort_menu)
                addLabel = stringResource(Res.string.composer_add)
                planLabel = stringResource(Res.string.template_plan)
                planPrompt = stringResource(Res.string.template_plan_prompt)
                HbTheme(darkTheme = false) {
                    Box(
                        Modifier.fillMaxSize().padding(HbTheme.spacing.xl),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        StudioComposer(state.paneContent(pane), events::add, isCompact = false)
                    }
                }
            }
            onNodeWithContentDescription(addLabel).assertIsDisplayed().performClick()
            onNodeWithText(autoLabel).performClick()
            onNodeWithContentDescription(effortLabel).assertIsDisplayed().performClick()
            onNodeWithText(lowLabel).performClick()
            onNodeWithContentDescription("Pulse Pro").performClick()
            onNodeWithText("Pulse Mini").performClick()
            onNodeWithContentDescription(addLabel).performClick()
            onNodeWithText(planLabel).performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(
                        StudioIntent.SelectApproval(ApprovalUi.AutoApprove, 0),
                        StudioIntent.SelectEffort(EffortUi.Low, 0),
                        StudioIntent.SelectModel("pulse-mini", 0),
                        StudioIntent.Suggestions.DraftChanged(0, planPrompt),
                    ),
                    events,
                )
            }
        }

    @Test
    fun `remember command leads the draft and typing continues after it`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val pane = PaneUi(0)
            var state by mutableStateOf(
                AiStudioScreenState(
                    panes = persistentListOf(pane),
                    models = StudioModelOptions,
                    isRememberEnabled = true,
                ),
            )
            var addLabel = ""
            var rememberLabel = ""
            setContent {
                addLabel = stringResource(Res.string.composer_add)
                rememberLabel = stringResource(Res.string.template_remember)
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        StudioComposer(
                            state.paneContent(pane),
                            { intent ->
                                if (intent is StudioIntent.Suggestions.DraftChanged) {
                                    state = state.withDraft(intent.paneId, intent.text)
                                }
                            },
                            isCompact = false,
                        )
                    }
                }
            }
            onNodeWithContentDescription(addLabel).performClick()
            onNodeWithText(rememberLabel).performClick()
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused().performTextInput("Use LF")
            runOnIdle { assertEquals("/remember Use LF", state.paneContent(pane).draft) }
        }

    @Test
    fun `native model composer omits preferences its runtime does not apply`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val pane = PaneUi(0)
            val native = ModelUi("native-route", "Native model")
            val initial = AiStudioScreenState(panes = persistentListOf(pane))
            val state = initial.copy(
                models = persistentListOf(native),
                settings = initial.settings.copy(modelId = native.id),
            )
            var lowLabel = ""
            var autoLabel = ""
            var addLabel = ""
            setContent {
                lowLabel = stringResource(Res.string.effort_low)
                autoLabel = stringResource(Res.string.approval_auto)
                addLabel = stringResource(Res.string.composer_add)
                HbTheme(darkTheme = true) {
                    Box(
                        Modifier.fillMaxSize().padding(HbTheme.spacing.xl),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        StudioComposer(state.paneContent(pane), {}, isCompact = false)
                    }
                }
            }
            onNodeWithTag("effort-chip").assertDoesNotExist()
            onNodeWithContentDescription(addLabel).performClick()
            onNodeWithText(autoLabel).assertDoesNotExist()
            onNodeWithText(lowLabel).assertDoesNotExist()
        }

    @Test
    fun `native model shows automatic safety without offering a trust bypass`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val pane = PaneUi(0)
            val native = ModelUi("native-route", "Native model", isTrustSupported = true)
            val initial = AiStudioScreenState(panes = persistentListOf(pane))
            val state = initial.copy(
                models = persistentListOf(native),
                settings = initial.settings.copy(modelId = native.id),
            )
            var editsLabel = ""
            var safetyLabel = ""
            var autoLabel = ""
            var addLabel = ""
            setContent {
                editsLabel = stringResource(Res.string.approval_edits)
                safetyLabel = stringResource(Res.string.approval_safety)
                autoLabel = stringResource(Res.string.approval_auto)
                addLabel = stringResource(Res.string.composer_add)
                NativeComposerFixture(state.paneContent(pane), events::add)
            }
            onNodeWithContentDescription(addLabel).performClick()
            onNodeWithText(editsLabel).assertDoesNotExist()
            onNodeWithText(autoLabel).assertDoesNotExist()
            onNodeWithText(safetyLabel).assertIsDisplayed().assertIsNotEnabled()
            runOnIdle {
                assertEquals(emptyList(), events)
            }
        }

    @Test
    fun `running native session offers model changes and a default effort is not replaced by its preference`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val pane = PaneUi(3, sessionId = "session")
            val native = ModelUi(
                "native-route",
                "Native model",
                reasoningEfforts = persistentListOf("medium", "high"),
                defaultReasoningEffort = "medium",
            )
            val next = ModelUi("next-route", "Next model")
            val initial = AiStudioScreenState()
            val state = initial.copy(
                panes = persistentListOf(pane),
                models = persistentListOf(native, next),
                running = persistentSetOf("session"),
                settings = initial.settings.copy(engineEfforts = persistentMapOf(native.id to "high")),
                configurations = persistentMapOf("session" to SessionConfigurationUi(native.id, null, ApprovalUi.Ask)),
            )
            var automatic = ""
            setContent {
                automatic = stringResource(Res.string.effort_default)
                NativeComposerFixture(state.paneContent(pane), events::add)
            }
            onNodeWithText(automatic).assertIsDisplayed()
            onNodeWithTag("model-chip").performClick()
            onNodeWithText(next.name).performClick()
            runOnIdle {
                assertEquals(listOf<StudioIntent>(StudioIntent.SelectModel(next.id, 3)), events)
            }
        }

    @Test
    fun `pending configuration disables setting controls but the template menu remains available`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val pane = PaneUi(3, sessionId = "session")
            val native = ModelUi(
                "native-route",
                "Native model",
                reasoningEfforts = persistentListOf("medium"),
                isTrustSupported = true,
            )
            val state = AiStudioScreenState(
                panes = persistentListOf(pane),
                models = persistentListOf(native),
                configurations = persistentMapOf(
                    "session" to SessionConfigurationUi(native.id, "medium", ApprovalUi.Ask, "operation"),
                ),
            )
            var addLabel = ""
            var autoLabel = ""
            var planLabel = ""
            var effortLabel = ""
            setContent {
                addLabel = stringResource(Res.string.composer_add)
                autoLabel = stringResource(Res.string.approval_safety)
                planLabel = stringResource(Res.string.template_plan)
                effortLabel = stringResource(Res.string.composer_effort_menu)
                NativeComposerFixture(state.paneContent(pane), {})
            }
            onNodeWithContentDescription(native.name).assertIsNotEnabled()
            onNodeWithContentDescription(effortLabel).assertIsNotEnabled()
            onNodeWithContentDescription(addLabel).performClick()
            onNodeWithText(autoLabel).assertIsNotEnabled()
            onNodeWithText(planLabel).assertIsDisplayed()
        }

    @Test
    fun `a chat without a project shows its connected harness and unchecking disconnects it`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val state = AiStudioScreenState(
                panes = persistentListOf(pane),
                harnessChoices = HarnessChoicesUi(
                    options = persistentListOf(HarnessOptionUi("compose", "Compose UI")),
                    chats = persistentMapOf("chat" to persistentSetOf("compose")),
                ),
            )
            var harnessLabel = ""
            setContent {
                harnessLabel = stringResource(Res.string.harness_menu_section)
                NativeComposerFixture(state.paneContent(pane), events::add)
            }

            onNodeWithContentDescription(harnessLabel).performClick()
            onNodeWithText("Compose UI").performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.SelectHarness(0, "compose", false)),
                    events,
                )
            }
        }

    @Test
    fun `a harness is unselected until the user picks it from the context toolbar`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val state = AiStudioScreenState(
                panes = persistentListOf(pane),
                harnessChoices = HarnessChoicesUi(options = persistentListOf(HarnessOptionUi("compose", "Compose UI"))),
            )
            var harnessLabel = ""
            setContent {
                harnessLabel = stringResource(Res.string.harness_menu_section)
                NativeComposerFixture(state.paneContent(pane), events::add)
            }

            onNodeWithContentDescription(harnessLabel).performClick()
            onNodeWithText("Compose UI").performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.SelectHarness(0, "compose", true)),
                    events,
                )
            }
        }
}

@Composable
private fun NativeComposerFixture(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    HbTheme(darkTheme = false) {
        Box(Modifier.fillMaxSize().padding(HbTheme.spacing.xl), contentAlignment = Alignment.BottomCenter) {
            StudioComposer(content, onIntent, isCompact = false)
        }
    }
}
