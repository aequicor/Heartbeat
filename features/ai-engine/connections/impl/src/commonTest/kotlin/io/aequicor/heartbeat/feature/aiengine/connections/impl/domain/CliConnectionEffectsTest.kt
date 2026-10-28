package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardMachineSpec
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionCheck
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.NewConnection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ClaudeId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.DesktopMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CliConnectionEffectsTest {
    private val facade = FakeEngineFacade()
    private val sources = FakeAuthSources()
    private val selections = FakeModelSelections()
    private val wizard = ConnectWizardEffects(EngineServices(facade, sources), selections)
    private val wizardScope = RecordingScope<ConnectWizardIntent>()
    private val codex = EngineId("codex")
    private val cli = DesktopMethod.copy(
        owner = AuthOwnerId("codex.cli"),
        location = AuthLocationId("codex.local"),
        hostApp = null,
    )
    private val cliCredential = CredentialInput.Existing("Codex", cli.origin)

    private suspend fun existingCli(engine: EngineId = codex, method: ConnectionMethod.CliLogin = cli): NewConnection {
        val source = sources.create(method, "Existing")
        val binding = EngineBinding(EngineBindingId("existing"), engine, source.info.id, priority = 7)
        facade.bindingsState.value = listOf(binding)
        selections.update { it.withEnabled(binding.id, setOf(ModelId("saved-model"))) }
        return NewConnection(
            binding.id,
            source.info.id,
            isCreated = false,
            initialModels = setOf(ModelId("saved-model")),
        )
    }

    private suspend fun finishReusedConnection(discoveryFails: Boolean, disabled: Boolean = false) {
        val existing = existingCli()
        if (disabled) facade.bindings.setEnabled(existing.binding, false)
        selections.update { it.withDefault(EngineTarget(codex, existing.binding, ModelId("saved-model"))) }
        val before = selections.state.value
        facade.discovered = listOf("saved-model")
        wizard.handle(ConnectWizardEffect.Connect(codex, cli, cliCredential), wizardScope)
        val connected = assertIs<ConnectWizardIntent.Internal.Connected>(wizardScope.intents.last())
        val info = engineInfo().let { it.copy(descriptor = it.descriptor.copy(id = codex)) }
        val spec = ConnectWizardMachineSpec
        val discovering = requireNotNull(spec.resolve(ConnectWizardState.Connecting(info, cli.id), connected))
        assertEquals(
            setOf(ModelId("saved-model")),
            assertIs<ConnectWizardState.ChoosingModels>(discovering.to).selected,
        )
        val discovery = assertIs<ConnectWizardEffect.DiscoverModels>(discovering.effects.single())
        val result = if (discoveryFails) {
            ConnectWizardIntent.Internal.ModelsFailed(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        } else {
            wizard.handle(discovery, wizardScope)
            wizardScope.intents.last()
        }
        val loaded = requireNotNull(spec.resolve(discovering.to, result))
        val saving = requireNotNull(spec.resolve(loaded.to, ConnectWizardIntent.Public.Finish))
        wizard.handle(assertIs<ConnectWizardEffect.SaveModels>(saving.effects.single()), wizardScope)
        assertEquals(before, selections.state.value)
        assertTrue(facade.bindingsState.value.single().isEnabled)
    }

    @Test
    fun `finishing a reused connection preserves its model choice and default`() = runTest {
        finishReusedConnection(discoveryFails = false)
    }

    @Test
    fun `finishing after failed discovery preserves the existing models and default`() = runTest {
        finishReusedConnection(discoveryFails = true)
    }

    @Test
    fun `finishing a disabled connection keeps it enabled with its saved models`() = runTest {
        finishReusedConnection(discoveryFails = false, disabled = true)
    }

    @Test
    fun `connecting a disabled CLI route enables it and cancelling restores its state`() = runTest {
        val existing = existingCli()
        facade.bindings.setEnabled(existing.binding, false)
        val before = selections.state.value

        wizard.handle(ConnectWizardEffect.Connect(codex, cli, cliCredential), wizardScope)
        val connected = assertIs<ConnectWizardIntent.Internal.Connected>(wizardScope.intents.last()).connection
        assertTrue(facade.bindingsState.value.single().isEnabled)
        assertFalse(connected.isInitiallyEnabled)
        wizard.handle(ConnectWizardEffect.Rollback(connected), wizardScope)

        assertFalse(facade.bindingsState.value.single().isEnabled)
        assertEquals(before, selections.state.value)
        assertTrue(sources.forgotten.isEmpty())
    }

    @Test
    fun `checking a disabled CLI route enables it for discovery then restores it`() = runTest {
        val existing = existingCli()
        facade.bindings.setEnabled(existing.binding, false)
        val gate = CompletableDeferred<Unit>()
        facade.discoverGate = gate
        val checking = launch {
            wizard.handle(ConnectWizardEffect.CheckConnection(codex, cli, cliCredential), wizardScope)
        }
        runCurrent()
        assertTrue(facade.bindingsState.value.single().isEnabled)
        gate.complete(Unit)
        checking.join()
        assertFalse(facade.bindingsState.value.single().isEnabled)
        assertTrue(sources.forgotten.isEmpty())
    }

    @Test
    fun `cancelled check restores a disabled CLI route`() = runTest {
        val existing = existingCli()
        facade.bindings.setEnabled(existing.binding, false)
        facade.discoverGate = CompletableDeferred()
        val checking = launch {
            wizard.handle(ConnectWizardEffect.CheckConnection(codex, cli, cliCredential), wizardScope)
        }
        runCurrent()
        assertTrue(facade.bindingsState.value.single().isEnabled)
        checking.cancel()
        checking.join()
        assertFalse(facade.bindingsState.value.single().isEnabled)
    }

    @Test
    fun `connecting an already bound CLI login reuses its binding without registering another source`() = runTest {
        val existing = existingCli()
        val before = facade.bindingsState.value

        wizard.handle(ConnectWizardEffect.Connect(codex, cli, cliCredential), wizardScope)

        assertEquals(ConnectWizardIntent.Internal.Connected(existing), wizardScope.intents.single())
        assertEquals(before, facade.bindingsState.value)
        assertEquals(listOf("refresh"), facade.calls)
        assertEquals(1, sources.state.value.size)
        assertTrue(sources.forgotten.isEmpty())
    }

    @Test
    fun `checking an already bound CLI login preserves its binding source and model selection`() = runTest {
        existingCli()
        val before = facade.bindingsState.value
        val models = selections.state.value

        wizard.handle(ConnectWizardEffect.CheckConnection(codex, cli, cliCredential), wizardScope)

        assertEquals(before, facade.bindingsState.value)
        assertEquals(models, selections.state.value)
        assertEquals(listOf("refresh", "discover"), facade.calls)
        assertEquals(1, sources.state.value.size)
        assertTrue(sources.forgotten.isEmpty())
        assertEquals(
            ConnectWizardIntent.Internal.ConnectionChecked(ConnectionCheck.Succeeded(2)),
            wizardScope.intents.single(),
        )
    }

    @Test
    fun `failed check preserves an existing CLI connection`() = runTest {
        existingCli()
        val before = facade.bindingsState.value
        val models = selections.state.value
        facade.discoverFailure = EngineFailure.Access(AccessFailureReason.OperationNotAllowed)

        assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.CheckConnection(codex, cli, cliCredential), wizardScope)
        }

        assertEquals(before, facade.bindingsState.value)
        assertEquals(models, selections.state.value)
        assertEquals(1, sources.state.value.size)
        assertTrue(sources.forgotten.isEmpty())
    }

    @Test
    fun `rolling back a reused CLI connection preserves saved settings`() = runTest {
        val existing = existingCli()
        val before = facade.bindingsState.value
        val models = selections.state.value

        wizard.handle(ConnectWizardEffect.Rollback(existing), wizardScope)

        assertEquals(before, facade.bindingsState.value)
        assertEquals(models, selections.state.value)
        assertTrue(facade.calls.isEmpty())
        assertEquals(1, sources.state.value.size)
        assertTrue(sources.forgotten.isEmpty())
        assertEquals(ConnectWizardIntent.Internal.RolledBack, wizardScope.intents.single())
    }

    @Test
    fun `rejected delivery preserves an existing CLI connection`() = runTest {
        existingCli()
        val before = facade.bindingsState.value
        val models = selections.state.value
        wizardScope.result = SendResult.Ignored

        wizard.handle(ConnectWizardEffect.Connect(codex, cli, cliCredential), wizardScope)

        assertEquals(before, facade.bindingsState.value)
        assertEquals(models, selections.state.value)
        assertTrue(sources.forgotten.isEmpty())
    }

    @Test
    fun `a different CLI account location is not reused`() = runTest {
        existingCli(method = cli.copy(location = AuthLocationId("other")))

        wizard.handle(ConnectWizardEffect.Connect(codex, cli, cliCredential), wizardScope)

        val connected = assertIs<ConnectWizardIntent.Internal.Connected>(wizardScope.intents.single())
        assertTrue(connected.connection.isCreated)
        assertEquals(2, facade.bindingsState.value.size)
    }

    @Test
    fun `a CLI login of another engine is not reused`() = runTest {
        existingCli(engine = ClaudeId)

        wizard.handle(ConnectWizardEffect.Connect(codex, cli, cliCredential), wizardScope)

        val connected = assertIs<ConnectWizardIntent.Internal.Connected>(wizardScope.intents.single())
        assertTrue(connected.connection.isCreated)
        assertEquals(2, facade.bindingsState.value.size)
        assertEquals(2, sources.state.value.size)
    }
}
