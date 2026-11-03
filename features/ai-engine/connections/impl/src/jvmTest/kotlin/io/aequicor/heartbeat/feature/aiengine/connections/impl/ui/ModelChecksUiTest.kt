package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.checkedSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.openCheckDetails
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.reflect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class ModelChecksUiTest {
    private val warning = EngineConnectionsScreenState().reflect(
        EngineConnectionsState.Active(checkedSettingsSnapshot()),
    )

    @Test
    fun `opening details does not execute a check and Escape returns focus to the information button`() =
        runSkikoComposeUiTest {
            var state by mutableStateOf(warning)
            val intents = mutableListOf<EngineConnectionsScreenIntent>()
            setContent {
                HbTheme {
                    EngineConnectionsContent(
                        state,
                        { intent ->
                            intents += intent
                            when (intent) {
                                is EngineConnectionsScreenIntent.ShowCheckDetails ->
                                    state = state.openCheckDetails(intent.target)

                                EngineConnectionsScreenIntent.DismissCheckDetails -> state = state.copy(
                                    checkDetails = null,
                                )

                                else -> Unit
                            }
                        },
                        {},
                        null,
                        { _, _ -> },
                    )
                }
            }
            onNodeWithTag("model-check-info:qwen").assertDoesNotExist()
            onNodeWithTag("model-check-info:llama").performClick()
            onNodeWithText("18|cedar|ABSENT", substring = true).assertHasNoClickAction()
            runOnIdle { assertEquals(1, intents.size) }
            onNodeWithTag("model-check-details").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("model-check-details").assertDoesNotExist()
            onNodeWithTag("model-check-info:llama").assertIsFocused()
            runOnIdle {
                assertEquals(EngineConnectionsScreenIntent.DismissCheckDetails, intents.last())
                assertEquals(2, intents.size)
            }
        }

    @Test
    fun `retry is explicit and becomes disabled during a pending check while close stays available`() =
        runSkikoComposeUiTest {
            val check = assertNotNull(warning.models?.models?.first()?.check)
            var state by mutableStateOf(warning.openCheckDetails(check.target))
            val intents = mutableListOf<EngineConnectionsScreenIntent>()
            setContent {
                HbTheme { EngineConnectionsContent(state, intents::add, {}, null, { _, _ -> }) }
            }
            onNodeWithTag("model-check-retry").performClick()
            runOnIdle {
                assertEquals(
                    listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.RetryModelCheck),
                    intents,
                )
                state = state.copy(isSaving = true)
            }
            onNodeWithTag("model-check-retry").assertIsNotEnabled().performClick()
            onNodeWithTag("model-check-close").performClick()
            runOnIdle {
                assertEquals(
                    listOf(
                        EngineConnectionsScreenIntent.RetryModelCheck,
                        EngineConnectionsScreenIntent.DismissCheckDetails,
                    ),
                    intents,
                )
            }
        }

    @Test
    fun `success removes information while keyboard focus restoration is pending`() = runSkikoComposeUiTest {
        val target = assertNotNull(warning.models?.models?.first()?.check).target
        var state by mutableStateOf(warning.openCheckDetails(target))
        setContent { HbTheme { EngineConnectionsContent(state, {}, {}, null, { _, _ -> }) } }
        waitForIdle()
        mainClock.autoAdvance = false
        runOnIdle { state = state.copy(checkDetails = null) }
        mainClock.advanceTimeByFrame()
        runOnIdle {
            state = state.reflect(EngineConnectionsState.Active(checkedSettingsSnapshot(isWarning = false)))
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        mainClock.autoAdvance = true
        onNodeWithTag("model-check-info:llama").assertDoesNotExist()
        onNodeWithTag("model-checkbox:llama").assertExists()
    }

    @Test
    fun `a successful current check has no information action`() = runSkikoComposeUiTest {
        val state = EngineConnectionsScreenState().reflect(
            EngineConnectionsState.Active(checkedSettingsSnapshot(isWarning = false)),
        )
        setContent { HbTheme { EngineConnectionsContent(state, {}, {}, null, { _, _ -> }) } }
        onNodeWithTag("model-check-info:llama").assertDoesNotExist()
        onNodeWithTag("model-checkbox:llama").assertExists()
    }
}
