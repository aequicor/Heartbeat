package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbStudioTheme
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EffortUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioModelOptions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_auto
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_effort_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_low
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan_prompt
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioComposerUiTest {
    @Test
    fun `native composer uses compact name and only advertised reasoning options`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
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
                    listOf<AiStudioScreenIntent>(AiStudioScreenIntent.SelectEngineEffort(model.id, "future-effort")),
                    events,
                )
            }
        }

    @Test
    fun `panel exposes working demo preferences and prompt templates`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
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
                    HbStudioTheme {
                        Box(
                            Modifier.fillMaxSize().padding(HbTheme.spacing.xl),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            StudioComposer(state.paneContent(pane), events::add, isCompact = false)
                        }
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
                    listOf<AiStudioScreenIntent>(
                        AiStudioScreenIntent.SelectApproval(ApprovalUi.AutoApprove),
                        AiStudioScreenIntent.SelectEffort(EffortUi.Low),
                        AiStudioScreenIntent.SelectModel("pulse-mini"),
                        AiStudioScreenIntent.DraftChanged(0, planPrompt),
                    ),
                    events,
                )
            }
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
}

@Composable
private fun NativeComposerFixture(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    HbTheme(darkTheme = false) {
        HbStudioTheme {
            Box(Modifier.fillMaxSize().padding(HbTheme.spacing.xl), contentAlignment = Alignment.BottomCenter) {
                StudioComposer(content, onIntent, isCompact = false)
            }
        }
    }
}
