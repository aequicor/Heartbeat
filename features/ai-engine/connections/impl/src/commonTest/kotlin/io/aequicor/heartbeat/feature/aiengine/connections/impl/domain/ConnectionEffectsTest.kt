package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.NewConnection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ApiKeyMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.OllamaMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.connections.impl.modelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEffectsTest {
    private val facade = FakeEngineFacade()
    private val sources = FakeAuthSources()
    private val selections = FakeModelSelections()
    private val services = EngineServices(facade, sources)
    private val wizard = ConnectWizardEffects(services, selections)
    private val settings = EngineConnectionsEffects(services, selections)
    private val wizardScope = RecordingScope<ConnectWizardIntent>()
    private val settingsScope = RecordingScope<EngineConnectionsIntent>()

    @Test
    fun `connecting creates a source and a binding and closes the key`() = runTest {
        val key = Secret("sk-test".toCharArray())
        val credential = CredentialInput.ApiKey("Work", ApiKeyMethod.origin, key)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, ApiKeyMethod, credential), wizardScope)
        val connection = NewConnection(EngineBindingId("binding-1"), AuthSourceId("source-1"))
        assertEquals(
            listOf<ConnectWizardIntent>(ConnectWizardIntent.Internal.Connected(connection)),
            wizardScope.intents,
        )
        assertEquals(listOf("refresh", "connect"), facade.calls)
        assertFailsWith<IllegalStateException> { key.reveal { } }
    }

    @Test
    fun `a connection the closed wizard can no longer accept is rolled back`() = runTest {
        wizardScope.result = SendResult.NotRunning
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
    }

    @Test
    fun `a rejected binding forgets the new source`() = runTest {
        facade.connectFailure = EngineFailure.Engine(EngineFailureReason.UnsupportedCapability)
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        val error = assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        }
        assertEquals(facade.connectFailure, error.failure)
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertTrue(sources.state.value.isEmpty())
    }

    @Test
    fun `an unavailable installation creates nothing`() = runTest {
        val failure = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)
        facade.availability = EngineAvailability.Unavailable(failure)
        val key = Secret("sk".toCharArray())
        val error = assertFailsWith<EngineException> {
            val credential = CredentialInput.ApiKey("Work", ApiKeyMethod.origin, key)
            wizard.handle(ConnectWizardEffect.Connect(KoogId, ApiKeyMethod, credential), wizardScope)
        }
        assertEquals(failure, error.failure)
        assertTrue(sources.state.value.isEmpty())
        assertFailsWith<IllegalStateException> { key.reveal { } }
    }

    @Test
    fun `discovered models are saved as the selection of the binding`() = runTest {
        val binding = EngineBindingId("binding-1")
        wizard.handle(ConnectWizardEffect.DiscoverModels(KoogId, binding), wizardScope)
        val models = listOf(modelInfo(binding, "gpt-a"), modelInfo(binding, "gpt-b"))
        assertEquals(ConnectWizardIntent.Internal.ModelsLoaded(models), wizardScope.intents.single())
        wizard.handle(ConnectWizardEffect.SaveModels(binding, setOf(ModelId("gpt-a"))), wizardScope)
        assertEquals(setOf(ModelId("gpt-a")), selections.state.value.enabled(binding))
        assertEquals(ConnectWizardIntent.Internal.Saved, wizardScope.intents.last())
    }

    @Test
    fun `rollback removes the binding, the source and the selection`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val connection = (wizardScope.intents.single() as ConnectWizardIntent.Internal.Connected).connection
        selections.update { it.withEnabled(connection.binding, setOf(ModelId("llama"))) }
        wizard.handle(ConnectWizardEffect.Rollback(connection), wizardScope)
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(connection.source), sources.forgotten)
        assertTrue(selections.state.value.bindings.isEmpty())
        assertEquals(ConnectWizardIntent.Internal.RolledBack, wizardScope.intents.last())
    }

    @Test
    fun `settings snapshot follows engines, sources, cached models and selection`() = runTest {
        val observer = backgroundScope.launch { settings.handle(EngineConnectionsEffect.Observe, settingsScope) }
        runCurrent()
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        facade.catalog.value = listOf(engineInfo(facade.bindingsState.value))
        runCurrent()
        val binding = facade.bindingsState.value.single().id
        settings.handle(
            EngineConnectionsEffect.Execute(ConnectionOperation.RefreshModels(KoogId, binding)),
            settingsScope,
        )
        runCurrent()
        val snapshot = assertIs<EngineConnectionsIntent.Internal.Snapshot>(
            settingsScope.intents.last { it is EngineConnectionsIntent.Internal.Snapshot },
        ).snapshot
        assertEquals(2, snapshot.models.getValue(binding).models.size)
        assertEquals(1, snapshot.sources.size)
        assertTrue(observer.isActive)
    }

    @Test
    fun `disconnect forgets a source no other binding uses`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val binding = facade.bindingsState.value.single().id
        val target = modelInfo(binding, "llama").target
        selections.update { it.withDefault(target) }
        settings.handle(EngineConnectionsEffect.Execute(ConnectionOperation.Disconnect(binding)), settingsScope)
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertEquals(null, selections.state.value.defaultTarget)
        assertEquals(EngineConnectionsIntent.Internal.Applied, settingsScope.intents.last())
    }

    @Test
    fun `model operations update the selection`() = runTest {
        val target = modelInfo(EngineBindingId("binding-1"), "gpt-a").target
        settings.handle(EngineConnectionsEffect.Execute(ConnectionOperation.SetDefaultModel(target)), settingsScope)
        assertEquals(target, selections.state.value.defaultTarget)
        settings.handle(
            EngineConnectionsEffect.Execute(ConnectionOperation.SetModelEnabled(target, false)),
            settingsScope,
        )
        assertEquals(null, selections.state.value.defaultTarget)
        assertTrue(selections.state.value.bindings.isEmpty())
    }
}
