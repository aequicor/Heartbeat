package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardMachineKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsMachineKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioCorrectionIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() {
        val os = System.getProperty("os.name")
        assumeTrue(os.startsWith("Windows") || os.startsWith("Mac") || os.startsWith("Linux"))
        // Profile services share Main in production; real IO must resume them serially on the test scheduler.
        Dispatchers.setMain(StandardTestDispatcher())
        TestAdapter.runtimes.clear()
    }

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    private suspend fun configured(): AiEngineTestAccessors {
        val toggles = app as TestToggleAccessors
        toggles.toggleControl.setOverride(AiEngines, true)
        toggles.toggleControl.setOverride(TestAdapter.toggle, true)
        toggles.toggleControl.setOverride(EngineConnectionsEnabled, true)
        toggles.toggleControl.setOverride(StudioEngineRuntime, true)
        val session = app.profileSessions.open(ProfileId("studio"))
        val services = session.graph as AiEngineTestAccessors
        val context = DefaultComponentContext(LifecycleRegistry().apply { resume() })
        val host = (session.graph as ProfileNavigation).navigation.create(
            context,
            listOf(EngineConnectionsRoute(isEmbedded = true)),
        )
        host.navigator.navigate(ConnectEngineRoute(TestAdapter.engine))
        val wizard = checkNotNull(app.machines.find(ConnectWizardMachineKey))
        wizard.state.first { it is ConnectWizardState.ChoosingMethod }
        app.machines.send(
            ConnectWizardMachineKey,
            ConnectWizardIntent.Public.Connect(
                ConnectionMethodId("key"),
                CredentialInput.ApiKey(
                    "Studio account",
                    EndpointOrigin("https://api.example.com"),
                    Secret("test-key".toCharArray()),
                ),
            ),
        )
        val models = wizard.state.first { it is ConnectWizardState.ChoosingModels && it.models != null }
            as ConnectWizardState.ChoosingModels
        app.machines.send(ConnectWizardMachineKey, ConnectWizardIntent.Public.SelectAllModels(true))
        app.machines.send(ConnectWizardMachineKey, ConnectWizardIntent.Public.Finish)
        wizard.state.first { it is ConnectWizardState.Finished }
        val target = EngineTarget(TestAdapter.engine, models.connection.binding, ModelId("m1"))
        // Wizard completion and the parent catalog's first observation are independent.
        requireNotNull(app.machines.find(EngineConnectionsMachineKey)).state.first {
            it is EngineConnectionsState.Active && it.snapshot != null && it.pending == null
        }
        assertEquals(
            SendResult.Accepted,
            app.machines.send(
                EngineConnectionsMachineKey,
                EngineConnectionsIntent.Public.Apply(ConnectionOperation.SetDefaultModel(target)),
            ),
        )
        services.modelSelections.observe().first { it.defaultTarget == target }
        return services
    }

    @Test
    fun correctionWithoutNativeSteeringStopsThenSubmitsExactlyOnce() = runStudioTest {
        val services = configured()
        val runtime = services.studioRuntime
        val chat = services.studioRepository.createSession(null, "Correction")
        val settings = runtime.defaults()
        val original = startAcceptedRun(runtime, chat.id, "Original", settings)
        val native = TestAdapter.runtimes.single().natives.single()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        native.cancelGate = gate
        val message = io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage(
            "correction",
            "Corrected input",
            emptyList(),
            settings,
        )
        runtime.enqueue(chat.id, message, null, isCorrection = true)
        runCurrent()
        assertEquals(1, native.sent.size)
        gate.complete(Unit)
        assertEquals(RunOutcome.Stopped, original.await())
        runtime.state.first { it.messageQueues.chat(chat.id).messages.isEmpty() }
        assertEquals(2, native.sent.size)
        assertEquals("correction", native.sent.last().id.value)
        native.finish()
        runtime.state.first { chat.id !in it.running }
        assertEquals(2, native.sent.size)
    }

    @Test
    fun manualStopRevokesAutomaticCorrectionWhileCancellationWaits() = runStudioTest {
        val services = configured()
        val runtime = services.studioRuntime
        val chat = services.studioRepository.createSession(null, "Stop correction")
        val settings = runtime.defaults()
        val original = startAcceptedRun(runtime, chat.id, "Original", settings)
        val native = TestAdapter.runtimes.single().natives.single()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        native.cancelGate = gate
        val message = io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage(
            "correction",
            "Retained input",
            emptyList(),
            settings,
        )
        runtime.enqueue(chat.id, message, null, isCorrection = true)
        runCurrent()
        val pending = assertFailsWith<EngineException> { runtime.cancel(chat.id) }
        assertEquals(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, native.sent.first().id),
            pending.failure,
        )
        gate.complete(Unit)
        original.await()
        runCurrent()
        assertEquals(1, native.sent.size)
        assertEquals(listOf(message), runtime.state.value.messageQueues.chat(chat.id).messages)
        assertTrue(runtime.state.value.messageQueues.chat(chat.id).isPaused)
    }

    /** Stops profile jobs before runTest drains its scheduler; @AfterTest would run too late for polling jobs. */
    private fun runStudioTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                app.closeAndAwaitStorages()
            }
        }
    }
}
