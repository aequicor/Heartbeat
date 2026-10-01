package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.OwnedScope
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineKey
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoice
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfiguration
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineKey
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioEngineIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() {
        val os = System.getProperty("os.name")
        assumeTrue(os.startsWith("Windows") || os.startsWith("Mac"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TestAdapter.runtimes.clear()
    }

    @AfterTest
    fun tearDown() = runTest {
        (app.appScope as OwnedScope).close()
        // close cancels without waiting; IO continuations must finish before replacing Dispatchers.Main.
        app.appScope.coroutineScope.coroutineContext[Job]?.join()
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
        app.machines.send(
            EngineConnectionsMachineKey,
            EngineConnectionsIntent.Public.Apply(ConnectionOperation.SetDefaultModel(target)),
        )
        services.modelSelections.observe().first { it.defaultTarget == target }
        return services
    }

    @Test
    fun `configuration read failure after native acceptance preserves outcome observation`() = runTest {
        TestAdapter.isConfigurationFailureEnabled = true
        try {
            val services = configured()
            val repository = services.studioRepository
            val runtime = services.studioRuntime
            val chat = repository.createSession(null, "Accepted configuration failure")
            val run = async { runtime.run(chat.id, "Keep observing the accepted turn", runtime.defaults()) }
            repository.observeMessages(chat.id).first { messages -> messages.any { it is StudioMessage.Prompt } }
            val native = TestAdapter.runtimes.single().natives.single()
            native.configurationReadFailures.first { it > 0 }
            assertEquals(1, native.sent.size)
            assertTrue(chat.id in runtime.state.value.running)
            assertFalse(run.isCompleted)
            assertEquals(0, native.cancellations)
            native.finish()
            assertEquals(RunOutcome.Completed, run.await())
            assertTrue(runtime.state.value.running.isEmpty())
            val messages = repository.observeMessages(chat.id).first { it.size == 2 }
            assertTrue(messages.none { it is StudioMessage.Failed })
        } finally {
            TestAdapter.isConfigurationFailureEnabled = false
        }
    }

    @Test
    fun `a fresh native conversation starts with the selected approval and effort defaults`() = runTest {
        TestAdapter.reasoningEfforts = listOf("low", "high")
        TestAdapter.isTrustSupported = true
        try {
            val services = configured()
            val repository = services.studioRepository
            val runtime = services.studioRuntime
            val settings = runtime.defaults().copy(approval = ApprovalMode.AutoApprove)
            repository.observeModels().first { it.singleOrNull()?.reasoningEfforts == listOf("low", "high") }
            val target = requireNotNull(services.modelSelections.observe().first().defaultTarget)
            val efforts = requireNotNull(app.machines.find(EffortConfigurationMachineKey))
            efforts.state.first { it is EffortConfigurationState.Ready }
            app.machines.send(EffortConfigurationMachineKey, EffortConfigurationIntent.Public.Select(target, "high"))
            val chat = repository.createSession(null, "Selected defaults")
            assertNull(runtime.state.value.configurations[chat.id])
            val run = async { runtime.run(chat.id, "Use the selected defaults", settings) }
            val applied = runtime.state.first { chat.id in it.configurations }.configurations.getValue(chat.id).applied
            val native = TestAdapter.runtimes.single().natives.single()
            assertEquals("high", native.sent.single().reasoningEffort)
            assertEquals(TrustLevel.Full, native.sent.single().trust)
            assertEquals("high", applied.reasoningEffort)
            assertEquals(ApprovalMode.AutoApprove, applied.approval)
            native.finish()
            assertEquals(RunOutcome.Completed, run.await())
        } finally {
            TestAdapter.reasoningEfforts = emptyList()
            TestAdapter.isTrustSupported = false
        }
    }

    @Test
    fun `start page choices survive profile restart and configure successive new conversations`() = runTest {
        TestAdapter.reasoningEfforts = listOf("low", "high")
        TestAdapter.isTrustSupported = true
        try {
            val services = configured()
            val settings = services.studioRuntime.defaults().copy(approval = ApprovalMode.AutoEdits)
            val target = requireNotNull(services.modelSelections.observe().first().defaultTarget)
            saveStartPageChoices(services, settings, target)
            assertTrue(services.studioRepository.observeWorkspace().first().sessions.isEmpty())
            app.profileSessions.close()

            val restored = app.profileSessions.open(ProfileId("studio")).graph as AiEngineTestAccessors
            assertEquals(settings, restored.studioRuntime.defaults())
            val choices = requireNotNull(app.machines.find(EffortConfigurationMachineKey)).state.value
            assertEquals("high", assertIs<EffortConfigurationState.Ready>(choices).effortFor(target))
            restored.studioRepository.observeModels().first { it.isNotEmpty() }
            repeat(2) { iteration ->
                val chat = restored.studioRepository.createSession(null, "Restored defaults $iteration")
                val run = async { restored.studioRuntime.run(chat.id, "Hello", restored.studioRuntime.defaults()) }
                restored.studioRuntime.state.first { chat.id in it.configurations }
                val native = TestAdapter.runtimes.last().natives.last()
                assertEquals("high", native.sent.single().reasoningEffort)
                assertEquals(TrustLevel.AutoEdits, native.sent.single().trust)
                native.finish()
                assertEquals(RunOutcome.Completed, run.await())
            }
            app.profileSessions.close()
            val isolated = app.profileSessions.open(ProfileId("isolated-defaults")).graph as AiEngineTestAccessors
            assertEquals(ApprovalMode.Ask, isolated.studioRuntime.defaults().approval)
            assertEquals("", isolated.studioRuntime.defaults().modelId)
        } finally {
            TestAdapter.reasoningEfforts = emptyList()
            TestAdapter.isTrustSupported = false
        }
    }

    private suspend fun saveStartPageChoices(
        services: AiEngineTestAccessors,
        settings: RunSettings,
        target: EngineTarget,
    ) {
        val lifecycle = LifecycleRegistry().apply { resume() }
        (services as ProfileNavigation).navigation.create(DefaultComponentContext(lifecycle), listOf(AiStudioRoute))
        requireNotNull(app.machines.find(AiStudioMachineKey)).state.first {
            it is AiStudioState.Ready && it.settings.modelId.isNotBlank()
        }
        app.machines.send(AiStudioMachineKey, AiStudioIntent.Public.UpdateSettings(settings))
        val stores = (services as TestStorageAccessors).stores
        stores.keyValue(KeyValueSpec("ai_studio_preferences"))
            .observe(jsonKey("new_session", JsonObject.serializer()))
            .first { it?.get("approval") == JsonPrimitive(settings.approval.name) }
        // Profile startup loads effort choices independently of the studio catalog and preferences.
        // Selecting while that machine is Loading is intentionally ignored by its public contract.
        requireNotNull(app.machines.find(EffortConfigurationMachineKey)).state.first {
            it is EffortConfigurationState.Ready
        }
        assertEquals(
            SendResult.Accepted,
            app.machines.send(EffortConfigurationMachineKey, EffortConfigurationIntent.Public.Select(target, "high")),
            "Effort selection must be accepted before waiting for its durable value",
        )
        stores.keyValue(KeyValueSpec("effort_configuration"))
            .observe(jsonKey("choices", ListSerializer(EffortChoice.serializer())))
            .first { it == listOf(EffortChoice(target, "high")) }
        lifecycle.destroy()
    }

    @Test
    fun `explicitly disabling effort persistence preserves model and approval persistence`() = runTest {
        (app as TestToggleAccessors).toggleControl.setOverride(EffortConfiguration, false)
        val services = configured()
        val settings = services.studioRuntime.defaults().copy(approval = ApprovalMode.AutoApprove)
        services.studioRuntime.saveDefaults(settings, StudioSettingsVersion("test", 1))
        val target = requireNotNull(services.modelSelections.observe().first().defaultTarget)
        app.machines.send(EffortConfigurationMachineKey, EffortConfigurationIntent.Public.Select(target, "high"))
        val before = requireNotNull(app.machines.find(EffortConfigurationMachineKey)).state.value
        assertEquals("high", assertIs<EffortConfigurationState.Ready>(before).effortFor(target))
        app.profileSessions.close()

        val restored = app.profileSessions.open(ProfileId("studio")).graph as AiEngineTestAccessors
        assertEquals(settings, restored.studioRuntime.defaults())
        val after = requireNotNull(app.machines.find(EffortConfigurationMachineKey)).state.value
        assertNull(assertIs<EffortConfigurationState.Ready>(after).effortFor(target))
    }

    @Test
    fun `legacy chat keeps its saved route when new chat defaults select another model`() = runTest {
        val services = configured()
        val repository = services.studioRepository
        val runtime = services.studioRuntime
        val settings = runtime.defaults()
        val savedTarget = requireNotNull(services.modelSelections.observe().first().defaultTarget)
        val chat = repository.createSession(null, "Legacy conversation")
        val store = (services as TestStorageAccessors).stores.keyValue(KeyValueSpec("ai_studio_chats"))
        val key = jsonKey("chats", JsonArray.serializer())
        val stored = requireNotNull(store.get(key)).single().jsonObject
        // Earlier versions stored the route without a session configuration snapshot.
        val legacy = JsonObject(
            stored.filterKeys { it != "configuration" } +
                ("target" to Json.encodeToJsonElement(EngineTarget.serializer(), savedTarget)),
        )
        store.set(key, JsonArray(listOf(legacy)))
        val otherDefaults = settings.copy(
            modelId = Json.encodeToString(EngineTarget.serializer(), savedTarget.copy(model = ModelId("other"))),
        )

        val run = async { runtime.run(chat.id, "Continue on the saved model", otherDefaults) }
        val messages = repository.observeMessages(chat.id).first { it.isNotEmpty() }
        assertIs<StudioMessage.Prompt>(messages.first())
        val native = TestAdapter.runtimes.single().natives.single()
        native.finish()
        assertEquals(RunOutcome.Completed, run.await())
        assertEquals(settings.modelId, runtime.state.value.configurations.getValue(chat.id).applied.modelId)
    }

    @Test
    fun studioMachineSubmitsThroughProfileEffectsAndLeavingScreenPreservesTheTurn() = runTest {
        val services = configured()
        val lifecycle = LifecycleRegistry().apply { resume() }
        (services as ProfileNavigation).navigation.create(
            DefaultComponentContext(lifecycle),
            listOf(AiStudioRoute),
        )
        val machine = checkNotNull(app.machines.find(AiStudioMachineKey))
        // The UI enables sending only after a model is offered; Ready can precede the initial catalog result.
        val ready = machine.state.first {
            it is AiStudioState.Ready && it.settings.modelId.isNotBlank()
        } as AiStudioState.Ready
        app.machines.send(AiStudioMachineKey, AiStudioIntent.Public.Submit(ready.focusedPaneId, "From the studio"))
        val running = machine.state.first { it is AiStudioState.Ready && it.running.isNotEmpty() }
            as AiStudioState.Ready
        val chat = running.running.single()
        services.studioRepository.observeMessages(chat).first { it.isNotEmpty() }
        val native = TestAdapter.runtimes.single().natives.single()
        lifecycle.destroy()
        assertTrue(chat in services.studioRuntime.state.value.running)
        assertEquals(0, native.cancellations)
        native.finish()
        services.studioRuntime.state.first { chat !in it.running }
        assertEquals(2, services.studioRepository.observeMessages(chat).first().size)
    }

    @Test
    fun connectionSelectionFeedsChatAndScreenDetachmentPreservesNativeExecution() = runTest {
        val services = configured()
        val repository = services.studioRepository
        val runtime = services.studioRuntime
        val settings = runtime.defaults()
        assertEquals(settings.modelId, repository.observeModels().first { it.isNotEmpty() }.single().id)
        val chat = repository.createSession(null, "Integrated conversation")
        val waiter = async { runtime.run(chat.id, "Hello engine", settings) }
        repository.observeMessages(chat.id).first { it.any { item -> item is StudioMessage.Prompt } }
        val native = TestAdapter.runtimes.single().natives.single()

        waiter.cancelAndJoin()
        assertTrue(chat.id in runtime.state.value.running)
        assertEquals(0, native.cancellations)
        native.finish()
        runtime.state.first { chat.id !in it.running }
        val messages = repository.observeMessages(chat.id).first { it.size == 2 }
        assertEquals("Hello engine", (messages.first() as StudioMessage.Prompt).text)
        assertEquals("Native answer", (messages.last() as StudioMessage.Reply).text)

        repository.edit(chat.id, SessionEdit.SetArchived(true))
        val binding = services.engineFacade.bindings.state.first { it.isNotEmpty() }.single()
        services.engineFacade.bindings.disconnect(binding.id)
        app.profileSessions.close()
        val restored = app.profileSessions.open(ProfileId("studio")).graph as AiEngineTestAccessors
        assertEquals(chat.id, restored.studioRepository.observeWorkspace().first().sessions.single().id)
        assertEquals(messages, restored.studioRepository.observeMessages(chat.id).first())
        app.profileSessions.close()
        val isolated = app.profileSessions.open(ProfileId("another")).graph as AiEngineTestAccessors
        assertTrue(isolated.studioRepository.observeWorkspace().first().sessions.isEmpty())
    }

    @Test
    fun indexedConversationResumesTheSameNativeSessionAfterHandleRelease() = runTest {
        val services = configured()
        val repository = services.studioRepository
        val runtime = services.studioRuntime
        val chat = repository.createSession(null, "Resume conversation")
        val first = async { runtime.run(chat.id, "First turn", runtime.defaults()) }
        repository.observeMessages(chat.id).first { it.isNotEmpty() }
        val native = TestAdapter.runtimes.single().natives.single()
        native.finish()
        assertEquals(RunOutcome.Completed, first.await())
        repository.edit(chat.id, SessionEdit.SetArchived(true))
        repository.edit(chat.id, SessionEdit.SetArchived(false))
        val second = async { runtime.run(chat.id, "Follow up", runtime.defaults()) }
        repository.observeMessages(chat.id).first { it.size == 3 }
        native.finish()
        assertEquals(RunOutcome.Completed, second.await())
        assertEquals(1, TestAdapter.runtimes.single().natives.size)
        assertEquals(4, repository.observeMessages(chat.id).first().size)
    }

    @Test
    fun archivingARunningChatReleasesItsBindingAfterNativeCompletion() = runTest {
        val services = configured()
        val runtime = services.studioRuntime
        val repository = services.studioRepository
        val chat = repository.createSession(null, "Archived during execution")
        val run = async { runtime.run(chat.id, "Continue in background", runtime.defaults()) }
        repository.observeMessages(chat.id).first { it.isNotEmpty() }
        repository.edit(chat.id, SessionEdit.SetArchived(true))
        assertTrue(chat.id in runtime.state.value.running)
        TestAdapter.runtimes.single().natives.single().finish()
        assertEquals(RunOutcome.Completed, run.await())
        val binding = services.engineFacade.bindings.state.first { it.isNotEmpty() }.single()
        services.engineFacade.bindings.disconnect(binding.id)
        assertTrue(services.engineFacade.bindings.state.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun profileShutdownCancelsAnArchivedSessionReleaseWithoutHangingTheRun() = runTest {
        val services = configured()
        val runtime = services.studioRuntime
        val repository = services.studioRepository
        val chat = repository.createSession(null, "Close race")
        val run = async { runtime.run(chat.id, "Archive then close profile", runtime.defaults()) }
        repository.observeMessages(chat.id).first { it.isNotEmpty() }
        val native = TestAdapter.runtimes.single().natives.single()
        native.closeGate = kotlinx.coroutines.CompletableDeferred()
        repository.edit(chat.id, SessionEdit.SetArchived(true))
        native.finish()
        native.closeStarted.await()
        app.profileSessions.close()
        run.join()
        assertTrue(run.isCancelled)
        assertTrue(runtime.state.value.running.isEmpty())
    }

    @Test
    fun asynchronousNativeStopFailureRemainsVisibleWithoutClaimingCompletion() = runTest {
        val services = configured()
        val runtime = services.studioRuntime
        val chat = services.studioRepository.createSession(null, "Failed stop")
        val run = async { runtime.run(chat.id, "Keep observing", runtime.defaults()) }
        services.studioRepository.observeMessages(chat.id).first { it.isNotEmpty() }
        val native = TestAdapter.runtimes.single().natives.single()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        native.cancelGate = gate
        native.isCancellationFailureEnabled = true
        runtime.cancel(chat.id)
        assertTrue(chat.id in runtime.state.value.running)
        gate.complete(Unit)
        runtime.state.first { chat.id in it.stopFailures }
        assertTrue(chat.id in runtime.state.value.running)
        native.finish()
        assertEquals(RunOutcome.Completed, run.await())
        assertTrue(runtime.state.value.stopFailures.isEmpty())
    }

    @Test
    fun exactPermissionDecisionAndExplicitStopReachNativeSession() = runTest {
        val services = configured()
        val runtime = services.studioRuntime
        val chat = services.studioRepository.createSession(null, "Permission conversation")
        val run = async { runtime.run(chat.id, "Change file", runtime.defaults()) }
        services.studioRepository.observeMessages(chat.id).first { it.isNotEmpty() }
        val native = TestAdapter.runtimes.single().natives.single()
        native.requestPermission()
        val pending = runtime.state.first { it.permissions.isNotEmpty() }.permissions.single()
        // A rejected answer fails, so the machine shows the request again.
        assertFailsWith<IllegalStateException> { runtime.respond(chat.id, pending.requestId, "invented-choice") }
        assertEquals(null, native.decision)
        runtime.respond(chat.id, pending.requestId, pending.options.single().id)
        assertEquals("allow-once", native.decision?.option?.value)
        runtime.cancel(chat.id)
        assertEquals(RunOutcome.Stopped, run.await())
        assertEquals(1, native.cancellations)
        assertTrue(runtime.state.value.running.isEmpty())
        assertTrue(runtime.state.value.permissions.isEmpty())
    }

    @Test
    fun stopRequestedWhileIdleDoesNotCancelTheNextRun() = runTest {
        val services = configured()
        val runtime = services.studioRuntime
        val chat = services.studioRepository.createSession(null, "Idle stop")
        runtime.cancel(chat.id)
        val run = async { runtime.run(chat.id, "Run after an idle stop", runtime.defaults()) }
        services.studioRepository.observeMessages(chat.id).first { it.isNotEmpty() }
        val native = TestAdapter.runtimes.single().natives.single()
        native.finish()
        assertEquals(RunOutcome.Completed, run.await())
        assertEquals(0, native.cancellations)
    }
}
