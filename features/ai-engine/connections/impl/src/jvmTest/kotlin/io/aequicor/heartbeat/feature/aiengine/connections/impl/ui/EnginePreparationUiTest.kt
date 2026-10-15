package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePreparationUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.PreparationPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.WizardStep
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EnginePreparationUiTest {
    @Test
    fun `a keyboard user can cancel while first use downloads the engine`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        setContent {
            HbTheme {
                ConnectWizardContent(
                    ConnectWizardScreenState(
                        step = WizardStep.Models,
                        isBusy = true,
                        preparation = EnginePreparationUi(PreparationPhaseUi.Downloading, 42),
                    ),
                    { intents += it },
                )
            }
        }
        onNodeWithTag("engine-preparation").assertIsDisplayed()
        val cancel = onNodeWithTag("wizard-cancel")
        cancel.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        cancel.requestFocus().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf<ConnectWizardScreenIntent>(ConnectWizardScreenIntent.Cancel), intents)
    }
}
