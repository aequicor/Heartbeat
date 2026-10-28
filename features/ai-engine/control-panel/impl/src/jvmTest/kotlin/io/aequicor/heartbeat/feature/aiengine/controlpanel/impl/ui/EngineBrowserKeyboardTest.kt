package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.CodexId
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlTarget
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineManagementIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FakeMachine
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchDraftUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.subscribe
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.testScopeHandle
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.testStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class EngineBrowserKeyboardTest {
    @Test
    fun `selecting an engine takes focus from settings and Escape asks before discarding its draft`() = runTest {
        val machine = FakeMachine<EngineControlState, EngineControlIntent, EngineControlOutput>(managedSnapshot())
        val model = EngineControlPanelModel(
            machine,
            EngineControlTarget(CodexId),
            testScopeHandle(),
            testStoreFactory(),
        )
        val screen = subscribe(model.store)
        val draft = LaunchDraftUi(executable = "/custom/codex")
        model.store.intent(EngineManagementIntent.EditLaunch(draft))
        runCurrent()
        var state by mutableStateOf(screen.states.value)
        var selected by mutableStateOf<String?>(null)
        var outerEvents = 0
        runSkikoComposeUiTest(size = Size(1016f, 1200f)) {
            setContent {
                HbTheme {
                    val outerFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { outerFocus.requestFocus() }
                    Box(
                        Modifier.focusRequester(outerFocus).onKeyEvent {
                            val isEscape = it.key == Key.Escape && it.type == KeyEventType.KeyUp
                            if (isEscape) outerEvents++
                            isEscape
                        }.focusable(),
                    ) {
                        EngineControlKeyboardScope(selected, {
                            model.store.intent(EngineControlPanelIntent.Back)
                            true
                        }) {
                            if (selected == null) {
                                HbButton("Select Codex", { selected = "codex" })
                            } else {
                                EngineControlPanelContent(state, { model.store.intent(it) }, isEmbedded = true)
                            }
                        }
                    }
                }
            }
            onNodeWithText("Select Codex").performClick()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            runCurrent()
            runOnIdle { state = screen.states.value }
            onNodeWithTag("leave-stay").assertExists().performClick()
            runCurrent()
            assertEquals(0, outerEvents)
            assertFalse(screen.states.value.isLeaving)
            assertEquals(draft, screen.states.value.launchDraft)
            assertTrue(screen.states.value.panel?.launch?.isDirty == true)
        }
    }
}
