package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionCheck
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.NewConnection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ApiKeyMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ClaudeId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.DesktopMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineManagement
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.OllamaMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.claudeManagement
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.connections.impl.modelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import kotlinx.coroutines.CompletableDeferred
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
    private val management = FakeEngineManagement()
    private val services = EngineServices(facade, sources, management)
    private val wizard = ConnectWizardEffects(services, selections)
    private val settings = EngineConnectionsEffects(services, selections)
    private val wizardScope = RecordingScope<ConnectWizardIntent>()
    private val settingsScope = RecordingScope<EngineConnectionsIntent>()

    @Test
    fun `closing settings cancels first use preparation during model refresh`() = runTest {
        val gate = CompletableDeferred<Unit>()
        facade.discoverGate = gate
        val discovery = launch {
            settings.handle(
                EngineConnectionsEffect.Execute(
                    ConnectionOperation.RefreshModels(KoogId, EngineBindingId("binding-1")),
                ),
                settingsScope,
            )
        }
        runCurrent()
        assertEquals(listOf("discover"), facade.calls)
        discovery.cancel()
        runCurrent()
        try {
            assertTrue(discovery.isCompleted)
            assertTrue(settingsScope.intents.isEmpty())
        } finally {
            gate.complete(Unit)
        }
    }

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
    fun `a host application connection saves the host runtime once the binding exists`() = runTest {
        val custom = LaunchSettings(executable = "/opt/claude", hostRuntimeVersion = "2.1.299")
        management.state.value = claudeManagement(custom)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        val saved = LaunchSettings(runtime = RuntimePreference.HostApp, hostRuntimeVersion = "2.1.299")
        assertEquals(listOf(EngineCommand.Inspect, EngineCommand.Configure(saved)), management.commands)
        assertEquals(1, facade.bindingsState.value.size)
        assertTrue(wizardScope.intents.single() is ConnectWizardIntent.Internal.Connected)
    }

    @Test
    fun `a host application connection waits for the inspection management publishes later`() = runTest {
        management.state.value = claudeManagement(LaunchSettings(runtime = RuntimePreference.HostApp))
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        management.publisher = backgroundScope
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        // Already on the host runtime: nothing to save, and the engine's availability is not probed first.
        assertEquals(listOf<EngineCommand>(EngineCommand.Inspect), management.commands)
        assertEquals(listOf("connect"), facade.calls)
        assertTrue(wizardScope.intents.single() is ConnectWizardIntent.Internal.Connected)
    }

    @Test
    fun `checking a host application connection needs the app's runtime and keeps the engine's runtime`() = runTest {
        management.state.value = claudeManagement()
        management.inspectedHostApp = HostAppState(isInstalled = true)
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)
        val missing = assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.CheckConnection(ClaudeId, DesktopMethod, credential), wizardScope)
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), missing.failure)

        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        wizard.handle(ConnectWizardEffect.CheckConnection(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(management.commands.none { it is EngineCommand.Configure })
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `a signed out CLI is signed in on the host runtime before the connection is written`() = runTest {
        management.state.value = claudeManagement(login = LoginState.SignedOut)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        val saved = LaunchSettings(runtime = RuntimePreference.HostApp)
        val commands = management.commands.filterNot { it == EngineCommand.Inspect }
        assertEquals(
            listOf(EngineCommand.Configure(saved), EngineCommand.Start(EngineAction.Login()), EngineCommand.Dismiss),
            commands,
        )
        assertEquals(1, facade.bindingsState.value.size)
        assertTrue(wizardScope.intents.single() is ConnectWizardIntent.Internal.Connected)
    }

    @Test
    fun `a signed in CLI is not signed in again`() = runTest {
        management.state.value = claudeManagement(login = LoginState.SignedIn("me"))
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.CheckConnection(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(management.commands.none { it is EngineCommand.Start })
    }

    @Test
    fun `a sign-in that never finishes is cancelled and left to the connection check`() = runTest {
        management.state.value = claudeManagement(login = LoginState.SignedOut)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        // The CLI prompts for a pasted code next to the sign-in page; the callback may still finish it.
        management.loginPhase = JobPhase.AwaitingCode("https://claude.ai/oauth/authorize")
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(management.commands.contains(EngineCommand.Cancel))
        assertTrue(wizardScope.intents.single() is ConnectWizardIntent.Internal.Connected)
    }

    @Test
    fun `a sign-in management refuses does not fail the connection`() = runTest {
        management.state.value = claudeManagement(login = LoginState.SignedOut)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        management.startFailure = EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(wizardScope.intents.single() is ConnectWizardIntent.Internal.Connected)
    }

    @Test
    fun `a runtime switched only to sign in is restored when the connection cannot be written`() = runTest {
        val custom = LaunchSettings(executable = "/opt/claude")
        management.state.value = claudeManagement(custom, LoginState.SignedOut)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        management.loginPhase = JobPhase.Failed(ManagementFailure.Login(LoginFailureReason.Rejected))
        facade.connectFailure = EngineFailure.Request(RequestFailureReason.Invalid)
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
        }

        assertEquals(custom, management.state.value.engines.single().launch.settings)
    }

    @Test
    fun `a check does not switch the runtime to sign in`() = runTest {
        management.state.value = claudeManagement(login = LoginState.SignedOut)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.CheckConnection(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(management.commands.none { it is EngineCommand.Configure || it is EngineCommand.Start })
    }

    @Test
    fun `a host application connection without the app's runtime or management creates nothing`() = runTest {
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)
        val off = assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
        }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), off.failure)

        management.state.value = claudeManagement()
        management.inspectedHostApp = HostAppState(isInstalled = true)
        val missing = assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), missing.failure)
        assertTrue(sources.state.value.isEmpty())
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `a host runtime that cannot be saved rolls the new connection back`() = runTest {
        management.state.value = claudeManagement()
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        management.configureFailure = EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
        }

        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
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
    fun `a connection check discovers models and keeps nothing`() = runTest {
        val key = Secret("sk-test".toCharArray())
        val credential = CredentialInput.ApiKey("Work", ApiKeyMethod.origin, key)
        wizard.handle(ConnectWizardEffect.CheckConnection(KoogId, ApiKeyMethod, credential), wizardScope)
        assertEquals(
            listOf<ConnectWizardIntent>(ConnectWizardIntent.Internal.ConnectionChecked(ConnectionCheck.Succeeded(2))),
            wizardScope.intents,
        )
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertFailsWith<IllegalStateException> { key.reveal { } }
    }

    @Test
    fun `a failed connection check removes the temporary connection`() = runTest {
        facade.discoverFailure = EngineFailure.Engine(EngineFailureReason.Unavailable)
        val key = Secret("sk-test".toCharArray())
        val credential = CredentialInput.ApiKey("Work", ApiKeyMethod.origin, key)
        val error = assertFailsWith<EngineException> {
            wizard.handle(ConnectWizardEffect.CheckConnection(KoogId, ApiKeyMethod, credential), wizardScope)
        }
        assertEquals(facade.discoverFailure, error.failure)
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertFailsWith<IllegalStateException> { key.reveal { } }
    }

    @Test
    fun `leaving the wizard while a check connects still removes what it created`() = runTest {
        val gate = CompletableDeferred<Unit>()
        facade.connectGate = gate
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        val check = launch {
            wizard.handle(ConnectWizardEffect.CheckConnection(KoogId, OllamaMethod, credential), wizardScope)
        }
        runCurrent()
        check.cancel()
        gate.complete(Unit)
        check.join()
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertTrue(wizardScope.intents.isEmpty())
    }

    @Test
    fun `choosing an engine probes each one and a failed probe keeps the list`() = runTest {
        facade.refreshFailure = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)
        val observation = launch { wizard.handle(ConnectWizardEffect.ObserveEngines, wizardScope) }
        runCurrent()
        assertEquals(listOf("refresh"), facade.calls)
        assertIs<ConnectWizardIntent.Internal.EnginesChanged>(wizardScope.intents.single())
        observation.cancel()
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
    fun `rollback removes the binding the source and the selection`() = runTest {
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
    fun `settings snapshot follows engines sources cached models and selection`() = runTest {
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
    fun `engine management reaches the snapshot`() = runTest {
        backgroundScope.launch { settings.handle(EngineConnectionsEffect.Observe, settingsScope) }
        runCurrent()
        val managed = EngineManagementState(isEnabled = true, platform = EnginePlatform.DesktopMacOs)
        management.state.value = managed
        runCurrent()

        val snapshot = assertIs<EngineConnectionsIntent.Internal.Snapshot>(settingsScope.intents.last()).snapshot
        assertEquals(managed, snapshot.management)
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
    fun `disconnect removes the last connection and models from the observed settings`() = runTest {
        backgroundScope.launch {
            facade.bindingsState.collect { facade.catalog.value = listOf(engineInfo(it)) }
        }
        backgroundScope.launch { settings.handle(EngineConnectionsEffect.Observe, settingsScope) }
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val binding = facade.bindingsState.value.single().id
        selections.update { it.withDefault(modelInfo(binding, "llama").target) }
        runCurrent()
        val before = settingsScope.intents.filterIsInstance<EngineConnectionsIntent.Internal.Snapshot>().last().snapshot
        assertEquals(listOf(binding), before.engines.single().bindings.map { it.id })

        settings.handle(EngineConnectionsEffect.Execute(ConnectionOperation.Disconnect(binding)), settingsScope)
        runCurrent()

        val after = settingsScope.intents.filterIsInstance<EngineConnectionsIntent.Internal.Snapshot>().last().snapshot
        assertTrue(after.engines.single().bindings.isEmpty())
        assertTrue(after.sources.isEmpty())
        assertTrue(after.models.isEmpty())
        assertTrue(after.selection.bindings.isEmpty())
        assertEquals(null, after.selection.defaultTarget)
    }

    @Test
    fun `retry finishes runtime cleanup after the binding has already been removed`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val binding = facade.bindingsState.value.single().id
        selections.update { it.withDefault(modelInfo(binding, "llama").target) }
        facade.disconnectCleanupFailure = IllegalStateException("runtime cleanup")
        val disconnect = EngineConnectionsEffect.Execute(ConnectionOperation.Disconnect(binding))

        assertFailsWith<IllegalStateException> { settings.handle(disconnect, settingsScope) }
        assertTrue(facade.bindingsState.value.isEmpty())
        assertTrue(sources.forgotten.isEmpty())

        facade.disconnectCleanupFailure = null
        settings.handle(disconnect, settingsScope)

        assertEquals(2, facade.calls.count { it == "disconnect" })
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertTrue(selections.state.value.bindings.isEmpty())
        assertEquals(EngineConnectionsIntent.Internal.Applied, settingsScope.intents.last())
    }

    @Test
    fun `rollback still forgets the source when clearing the model choice fails`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val connection = (wizardScope.intents.single() as ConnectWizardIntent.Internal.Connected).connection
        selections.updateFailure = IllegalStateException("store closed")
        wizard.handle(ConnectWizardEffect.Rollback(connection), wizardScope)
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(listOf(connection.source), sources.forgotten)
        assertEquals(ConnectWizardIntent.Internal.RolledBack, wizardScope.intents.last())
    }

    @Test
    fun `rollback keeps the source while its binding could not be removed`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val connection = (wizardScope.intents.single() as ConnectWizardIntent.Internal.Connected).connection
        facade.disconnectFailure = IllegalStateException("runtime busy")
        wizard.handle(ConnectWizardEffect.Rollback(connection), wizardScope)
        assertEquals(1, facade.bindingsState.value.size)
        assertTrue(sources.forgotten.isEmpty())
        assertEquals(ConnectWizardIntent.Internal.RolledBack, wizardScope.intents.last())
    }

    @Test
    fun `disconnect keeps a source another binding still uses`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val first = facade.bindingsState.value.single()
        val second = facade.bindings.connect(KoogId, first.authSource, 0)
        settings.handle(EngineConnectionsEffect.Execute(ConnectionOperation.Disconnect(first.id)), settingsScope)
        assertEquals(listOf(second), facade.bindingsState.value)
        assertTrue(sources.forgotten.isEmpty())
    }

    @Test
    fun `a disconnect retried after a failed choice update only clears the choice`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val binding = facade.bindingsState.value.single().id
        selections.update { it.withEnabled(binding, setOf(ModelId("llama"))) }
        selections.updateFailure = IllegalStateException("store closed")
        val disconnect = EngineConnectionsEffect.Execute(ConnectionOperation.Disconnect(binding))
        assertFailsWith<IllegalStateException> { settings.handle(disconnect, settingsScope) }
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        selections.updateFailure = null
        settings.handle(disconnect, settingsScope)
        assertTrue(selections.state.value.bindings.isEmpty())
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertEquals(EngineConnectionsIntent.Internal.Applied, settingsScope.intents.last())
    }

    @Test
    fun `a disconnect retried after a failed forget still forgets the source`() = runTest {
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        wizard.handle(ConnectWizardEffect.Connect(KoogId, OllamaMethod, credential), wizardScope)
        val binding = facade.bindingsState.value.single().id
        sources.forgetFailure = IllegalStateException("vault locked")
        val disconnect = EngineConnectionsEffect.Execute(ConnectionOperation.Disconnect(binding))
        assertFailsWith<IllegalStateException> { settings.handle(disconnect, settingsScope) }
        assertTrue(facade.bindingsState.value.isEmpty())
        sources.forgetFailure = null
        settings.handle(disconnect, settingsScope)
        assertEquals(listOf(AuthSourceId("source-1")), sources.forgotten)
        assertTrue(sources.state.value.isEmpty())
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
