package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.NewConnection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ApiKeyMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.connections.impl.modelInfo
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.AccessGroupUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.CredentialForm
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.SecretText
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.reflect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_connect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_hide_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_save
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_show_key
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectWizardUiTest {
    @Test
    fun `access group offers only declared categories and opens provider choices`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        val state = ConnectWizardScreenState().reflect(ConnectWizardState.ChoosingMethod(engineInfo()))
        setContent { HbTheme { ConnectWizardContent(state, { intents += it }) } }
        onNodeWithTag("access-group:Provider").assertIsFocused().performClick()
        onNodeWithTag("access-group:Subscription").assertDoesNotExist()
        runOnIdle {
            assertEquals(
                listOf<ConnectWizardScreenIntent>(ConnectWizardScreenIntent.SelectGroup(AccessGroupUi.Provider)),
                intents,
            )
        }
    }

    @Test
    fun `provider list opens its selected method`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        val state = ConnectWizardScreenState().reflect(ConnectWizardState.ChoosingMethod(engineInfo()))
            .copy(selectedGroup = AccessGroupUi.Provider)
        setContent { HbTheme { ConnectWizardContent(state, { intents += it }) } }
        onNodeWithTag("method:${ApiKeyMethod.id.value}").assertIsFocused().performClick()
        runOnIdle {
            assertEquals(
                listOf<ConnectWizardScreenIntent>(ConnectWizardScreenIntent.SelectMethod(ApiKeyMethod.id.value)),
                intents,
            )
        }
    }

    @Test
    fun `engines that cannot run here are shown but not selectable`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        val mobileOnly = engineInfo(availability = EngineAvailability.UnsupportedPlatform).let {
            it.copy(descriptor = it.descriptor.copy(id = EngineId("pi"), title = "Pi"))
        }
        val engines = listOf(engineInfo(), mobileOnly)
        val state = ConnectWizardScreenState().reflect(ConnectWizardState.ChoosingEngine(engines))
        setContent { HbTheme { ConnectWizardContent(state, { intents += it }) } }
        onNodeWithTag("engine:pi").assertIsNotEnabled()
        onNodeWithTag("engine:koog").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(
                listOf<ConnectWizardScreenIntent>(ConnectWizardScreenIntent.ChooseEngine("koog")),
                intents,
            )
        }
    }

    @Test
    fun `method step edits the selected provider and connects`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        val state = ConnectWizardScreenState().reflect(ConnectWizardState.ChoosingMethod(engineInfo())).copy(
            selectedGroup = AccessGroupUi.Provider,
            selectedMethod = ApiKeyMethod.id.value,
        )
        setContent { HbTheme { ConnectWizardContent(state, { intents += it }) } }
        onNodeWithTag("wizard-host").assertIsNotEnabled()
        onNodeWithTag("wizard-key").performTextInput("sk")
        onNodeWithTag("wizard-connect").performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    ConnectWizardScreenIntent.EditKey("sk"),
                    ConnectWizardScreenIntent.Connect,
                ),
                intents,
            )
        }
    }

    @Test
    fun `key stays secure while visibility actions reveal and mask its value`() = runSkikoComposeUiTest {
        val state = ConnectWizardScreenState().reflect(ConnectWizardState.ChoosingMethod(engineInfo())).copy(
            selectedGroup = AccessGroupUi.Provider,
            selectedMethod = ApiKeyMethod.id.value,
            form = CredentialForm(key = SecretText("sk-123")),
        )
        setContent { HbTheme { ConnectWizardContent(state, {}) } }
        val field = onNodeWithTag("wizard-key")
        var config = field.fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        assertEquals("••••••", config[SemanticsProperties.EditableText].text)
        onNodeWithContentDescription(getString(Res.string.wizard_show_key)).assertExists().performClick()
        config = field.fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        assertEquals("sk-123", config[SemanticsProperties.EditableText].text)
        onNodeWithContentDescription(getString(Res.string.wizard_hide_key)).assertExists().performClick()
        config = field.fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        assertEquals("••••••", config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun `models step toggles models and finishes`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        val connection = NewConnection(EngineBindingId("binding-1"), AuthSourceId("source-1"))
        val models = listOf(modelInfo(connection.binding, "gpt-a"), modelInfo(connection.binding, "gpt-b"))
        val machine = ConnectWizardState.ChoosingModels(KoogId, connection, models, setOf(ModelId("gpt-a")))
        val state = ConnectWizardScreenState().reflect(machine)
        setContent { HbTheme { ConnectWizardContent(state, { intents += it }) } }
        onNodeWithText(getString(Res.string.wizard_connect)).assertExists()
        onNodeWithTag("model-checkbox:gpt-b").performClick()
        onNodeWithTag("wizard-finish").performClick()
        runOnIdle {
            assertEquals(
                listOf(ConnectWizardScreenIntent.ToggleModel("gpt-b"), ConnectWizardScreenIntent.Finish),
                intents,
            )
        }
    }

    @Test
    fun `reused connection offers to save its models`() = runSkikoComposeUiTest {
        val connection = NewConnection(EngineBindingId("binding-1"), AuthSourceId("source-1"), isCreated = false)
        val models = listOf(modelInfo(connection.binding, "gpt-a"))
        val machine = ConnectWizardState.ChoosingModels(KoogId, connection, models, setOf(ModelId("gpt-a")))
        val state = ConnectWizardScreenState().reflect(machine)
        setContent { HbTheme { ConnectWizardContent(state, {}) } }
        onNodeWithText(getString(Res.string.wizard_save)).assertExists()
    }

    @Test
    fun `escape is the wizard's own back`() = runSkikoComposeUiTest {
        val intents = mutableListOf<ConnectWizardScreenIntent>()
        val state = ConnectWizardScreenState().reflect(ConnectWizardState.ChoosingMethod(engineInfo()))
        setContent { HbTheme { ConnectWizardContent(state, { intents += it }) } }
        onNodeWithTag("connect-wizard").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(listOf<ConnectWizardScreenIntent>(ConnectWizardScreenIntent.SystemBack), intents) }
    }
}
