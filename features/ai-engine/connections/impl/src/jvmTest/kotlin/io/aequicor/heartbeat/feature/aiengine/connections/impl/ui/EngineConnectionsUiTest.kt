package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.reflect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.settingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EngineConnectionsUiTest {
    private val loaded = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(settingsSnapshot()))

    @Test
    fun `engine, connection and model levels are shown together`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        val wizard = mutableListOf<String?>()
        setContent {
            HbTheme { EngineConnectionsContent(loaded, { intents += it }, { wizard += it }, {}) }
        }
        onNodeWithTag("settings-engine:koog").assertIsSelected()
        onNodeWithTag("settings-connection:home").assertIsSelected()
        onNodeWithTag("settings-connection:work").performClick()
        onNodeWithTag("model-switch:qwen").performClick()
        onNodeWithTag("settings-default:qwen").performClick()
        onNodeWithTag("settings-add-connection").performClick()
        onNodeWithTag("settings-add-engine").performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    EngineConnectionsScreenIntent.SelectConnection("work"),
                    EngineConnectionsScreenIntent.SetModelEnabled("qwen", true),
                    EngineConnectionsScreenIntent.SetDefaultModel("qwen"),
                ),
                intents,
            )
            assertEquals(listOf("koog", null), wizard)
        }
    }

    @Test
    fun `pending writes disable mutations before progress appears and enable them on completion`() =
        runSkikoComposeUiTest(size = Size(1000f, 1600f)) {
            var state by mutableStateOf(loaded)
            val intents = mutableListOf<EngineConnectionsScreenIntent>()
            val tags = listOf(
                "settings-probe",
                "settings-refresh-models",
                "settings-disconnect",
                "settings-connection-enabled",
                "settings-models-all",
                "settings-models-none",
                "model-switch:qwen",
                "settings-default:qwen",
            )
            setContent { HbTheme { EngineConnectionsContent(state, intents::add, {}, null) } }
            tags.forEach { onNodeWithTag(it).assertIsEnabled() }
            mainClock.autoAdvance = false
            runOnIdle { state = loaded.copy(isSaving = true) }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("settings-saving").assertDoesNotExist()
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsNotEnabled()
                onNodeWithTag(tag).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
            }
            runOnIdle { assertEquals(emptyList(), intents) }
            onNodeWithTag("settings-model-search").assertIsEnabled()
            mainClock.advanceTimeBy(HbMotion().busyRevealDelayMillis.toLong() * 2)
            onNodeWithTag("settings-saving").assertExists()
            tags.forEach { onNodeWithTag(it).assertIsNotEnabled() }
            runOnIdle { state = loaded }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("settings-saving").assertDoesNotExist()
            tags.forEach { onNodeWithTag(it).assertIsEnabled() }
            onNodeWithTag("model-switch:qwen").performClick()
            runOnIdle {
                assertEquals(
                    listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true)),
                    intents,
                )
            }
        }

    @Test
    fun `disconnect asks for confirmation`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineConnectionsScreenIntent>()
        setContent {
            HbTheme { EngineConnectionsContent(loaded.copy(confirmDisconnect = "home"), { intents += it }, {}, {}) }
        }
        onNodeWithTag("settings-disconnect-confirm").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineConnectionsScreenIntent>(EngineConnectionsScreenIntent.ConfirmDisconnect),
                intents,
            )
        }
    }
}
