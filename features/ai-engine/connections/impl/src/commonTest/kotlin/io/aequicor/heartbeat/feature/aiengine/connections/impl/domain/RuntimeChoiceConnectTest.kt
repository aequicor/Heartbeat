package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardRuntimeChoice
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ClaudeId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.DesktopMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineManagement
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.OllamaMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.claudeManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineOwnedJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeChoiceConnectTest {
    private val facade = FakeEngineFacade()
    private val sources = FakeAuthSources()
    private val selections = FakeModelSelections()
    private val management = FakeEngineManagement()
    private val original = LaunchSettings(executable = "/old")
    private val selected = original.copy(executable = "/new")
    private val scope = RecordingScope<ConnectWizardIntent>()
    private var inspectionOverride: EngineCandidateInspection? = null

    @Test
    fun `local check without a declared source does not inspect or change settings`() = runTest {
        val wizard = ConnectWizardEffects(EngineServices(facade, sources, management), selections, FakeModelChecks())
        wizard.handle(ConnectWizardEffect.CheckLocalRuntime(ClaudeId, null), scope)
        assertEquals(ConnectWizardIntent.Internal.RuntimeFilePicked(null), scope.intents.single())
        assertTrue(management.commands.isEmpty())
    }

    @Test
    fun `closing the wizard leaves its profile installation running`() = runTest {
        var cancelled = false
        val handle = object : EngineOwnedJob {
            override suspend fun await(): JobPhase = awaitCancellation()
            override suspend fun cancel(): JobPhase {
                cancelled = true
                return JobPhase.Cancelled
            }
        }
        val owned = object : EngineManagement by management {
            override suspend fun startOwned(
                engine: EngineId,
                action: EngineAction,
                onStarted: (EngineOwnedJob) -> Unit,
            ) {
                assertEquals(ClaudeId, engine)
                assertEquals(EngineAction.Install, action)
                onStarted(handle)
            }
        }
        val wizard = ConnectWizardEffects(EngineServices(facade, sources, owned), selections, FakeModelChecks())
        val job = launch { wizard.handle(ConnectWizardEffect.InstallManagedRuntime(ClaudeId), scope) }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(cancelled)
        assertEquals(
            listOf<ConnectWizardIntent>(ConnectWizardIntent.Internal.RuntimeInstallationStarted(handle)),
            scope.intents,
        )
    }

    private suspend fun choice(): WizardRuntimeChoice {
        management.state.value = claudeManagement(original)
        val snapshot = requireNotNull(management.inspect(ClaudeId).savedLaunch)
        return WizardRuntimeChoice(snapshot, selected, report())
    }

    private fun effects(): ConnectWizardEffects {
        val checked = object : EngineManagement by management {
            override suspend fun inspectCandidate(
                engine: EngineId,
                expected: LaunchSettingsSnapshot,
                candidate: LaunchSettings,
            ): EngineCandidateInspection {
                assertEquals(ClaudeId, engine)
                assertEquals(original, expected.settings)
                assertEquals(selected, candidate)
                return inspectionOverride ?: report()
            }
        }
        return ConnectWizardEffects(EngineServices(facade, sources, checked), selections, FakeModelChecks())
    }

    @Test
    fun `confirmed file choice is carried into rollback receipt`() = runTest {
        val choice = choice()
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        effects().handle(ConnectWizardEffect.Connect(ClaudeId, OllamaMethod, credential, choice), scope)
        assertEquals(selected, management.state.value.engines.single().launch.settings)
        val connected = assertIs<ConnectWizardIntent.Internal.Connected>(scope.intents.single())
        assertEquals(original, connected.connection.launchRollback?.previous)
    }

    @Test
    fun `failed connection restores owned launch choice`() = runTest {
        val choice = choice()
        facade.connectFailure = EngineFailure.Request(RequestFailureReason.Invalid)
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        assertFailsWith<EngineException> {
            effects().handle(ConnectWizardEffect.Connect(ClaudeId, OllamaMethod, credential, choice), scope)
        }
        assertEquals(original, management.state.value.engines.single().launch.settings)
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `concurrent launch change wins over the picked choice`() = runTest {
        val choice = choice()
        val other = original.copy(executable = "/other")
        management.execute(ClaudeId, EngineCommand.Configure(other))
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        assertFailsWith<EngineException> {
            effects().handle(ConnectWizardEffect.Connect(ClaudeId, OllamaMethod, credential, choice), scope)
        }
        assertEquals(other, management.state.value.engines.single().launch.settings)
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `missing installation cannot be saved even with a positive report`() = runTest {
        val choice = choice()
        inspectionOverride = report().copy(installation = Installation(InstallSource.Missing))
        val credential = CredentialInput.Existing("Local", OllamaMethod.origin)
        assertFailsWith<EngineException> {
            effects().handle(ConnectWizardEffect.Connect(ClaudeId, OllamaMethod, credential, choice), scope)
        }
        assertEquals(original, management.state.value.engines.single().launch.settings)
        assertTrue(facade.bindingsState.value.isEmpty())
    }

    @Test
    fun `cli sign in cannot start from observational choice`() = runTest {
        val choice = choice()
        val credential = CredentialInput.Existing("CLI", DesktopMethod.origin)
        assertFailsWith<EngineException> {
            effects().handle(ConnectWizardEffect.Connect(ClaudeId, DesktopMethod, credential, choice), scope)
        }
        assertEquals(original, management.state.value.engines.single().launch.settings)
        assertEquals(listOf<EngineCommand>(EngineCommand.Inspect), management.commands)
    }

    private fun report(): EngineCandidateInspection {
        val protocol = EngineProtocolIdentity.Negotiated("test", 1)
        val policy = EngineCompatibilityRequirements(
            ClaudeId, setOf("host"), setOf("connector"), "1.0.0", protocols = setOf(protocol),
            configurationSchemas = setOf(1), historySchemas = setOf(1), nativeTargets = setOf("target"),
            baselineCapabilities = emptySet(),
        )
        val evidence = EngineCompatibilityEvidence(
            EngineCompatibilityIdentity(ClaudeId, "host", "connector", "1.0.0", protocol, 1, 1, "target", "hash"),
            true,
            true,
            emptySet(),
        )
        return EngineCandidateInspection(
            ClaudeId,
            Installation(InstallSource.Custom, "1.0.0", "/new"),
            EngineCompatibilityObservation(policy, evidence),
        )
    }
}
