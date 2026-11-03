package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ClaudeId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.DesktopMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineManagement
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.claudeManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectWizardLaunchRaceTest {
    private val facade = FakeEngineFacade()
    private val sources = FakeAuthSources()
    private val selections = FakeModelSelections()
    private val management = FakeEngineManagement()
    private val services = EngineServices(facade, sources, management)
    private val wizard = ConnectWizardEffects(services, selections, FakeModelChecks())
    private val wizardScope = RecordingScope<ConnectWizardIntent>()

    @Test
    fun `a concurrent configure is not overwritten when a connection fails`() = runTest {
        management.state.value = claudeManagement(LaunchSettings(executable = "/old"), LoginState.SignedOut)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val gate = CompletableDeferred<Unit>()
        facade.connectGate = gate
        facade.connectFailure = EngineFailure.Request(RequestFailureReason.Invalid)
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)
        val result = async {
            assertFailsWith<EngineException> {
                wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
            }
        }
        runCurrent()
        val other = LaunchSettings(executable = "/other")
        management.execute(ClaudeId, EngineCommand.Configure(other))
        gate.complete(Unit)
        result.await()
        assertEquals(other, management.state.value.engines.single().launch.settings)
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `a concurrent configure rejects the final host choice and removes the new connection`() = runTest {
        management.state.value = claudeManagement(LaunchSettings(executable = "/old"))
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val gate = CompletableDeferred<Unit>()
        facade.connectGate = gate
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)
        val result = async {
            assertFailsWith<EngineException> {
                wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
            }
        }
        runCurrent()
        val other = LaunchSettings(executable = "/other")
        management.execute(ClaudeId, EngineCommand.Configure(other))
        gate.complete(Unit)
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), result.await().failure)
        assertEquals(other, management.state.value.engines.single().launch.settings)
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `cancellation after confirmed host choice rolls back the connection and owned setting`() = runTest {
        val original = LaunchSettings(executable = "/old")
        management.state.value = claudeManagement(original)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val gate = CompletableDeferred<Unit>()
        management.afterConfigure = { gate.await() }
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)
        val job = launch {
            wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
        }
        runCurrent()
        assertEquals(RuntimePreference.HostApp, management.state.value.engines.single().launch.settings.runtime)
        assertEquals(1, facade.bindingsState.value.size)
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(original, management.state.value.engines.single().launch.settings)
        assertTrue(wizardScope.intents.isEmpty())
    }

    @Test
    fun `failure after confirmed host choice rolls back the connection and owned setting`() = runTest {
        val original = LaunchSettings(executable = "/old")
        management.state.value = claudeManagement(original)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        management.afterConfigure = {
            management.afterConfigure = {}
            throw IllegalStateException("retirement failed")
        }
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)
        assertFailsWith<IllegalStateException> {
            wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)
        }
        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(original, management.state.value.engines.single().launch.settings)
    }

    @Test
    fun `a closed wizard restores the owned host choice along with the connection`() = runTest {
        val original = LaunchSettings(executable = "/old")
        management.state.value = claudeManagement(original)
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        wizardScope.result = SendResult.NotRunning
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(original, management.state.value.engines.single().launch.settings)
    }

    @Test
    fun `closing the wizard does not undo a newer choice made before delivery`() = runTest {
        management.state.value = claudeManagement(LaunchSettings(executable = "/old"))
        management.inspectedHostApp = HostAppState(true, runtimes = listOf(HostRuntime("2.1.299", "b", true, "/c")))
        val other = LaunchSettings(executable = "/other")
        wizardScope.beforeSend = { management.execute(ClaudeId, EngineCommand.Configure(other)) }
        wizardScope.result = SendResult.NotRunning
        val credential = CredentialInput.Existing("Claude", DesktopMethod.origin)

        wizard.handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential), wizardScope)

        assertTrue(facade.bindingsState.value.isEmpty())
        assertEquals(other, management.state.value.engines.single().launch.settings)
    }
}
