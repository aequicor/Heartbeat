package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
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
