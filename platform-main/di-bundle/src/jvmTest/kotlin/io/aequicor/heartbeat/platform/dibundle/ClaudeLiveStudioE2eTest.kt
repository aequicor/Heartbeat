package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome as StudioRunOutcome

/**
 * Opt-in regression of the studio chat on the real Claude Code CLI, outside `check`:
 * `./gradlew :platform-main:di-bundle:claudeLiveGraphTest`. It drives the connection wizard machine with the CLI
 * login, picks the default model, then runs a chat through the studio runtime and repository (the engine runtime
 * toggle is on), and finally restarts the whole graph over the same storage and continues the same chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeLiveStudioE2eTest {
    private val persisted = PersistedProfile()
    private val graphs = mutableListOf<TestAppGraph>()

    @BeforeTest
    fun setUp() {
        assumeTrue(System.getProperty("heartbeat.claude.liveTest") == "true")
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        runBlocking { graphs.forEach { it.closeAndAwaitStorages() } }
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `a studio chat on the connected Claude login answers and continues after a restart`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val first = graph()
            val services = openProfile(first, connect = true)
            val repository = services.studioRepository
            val runtime = services.studioRuntime
            val settings = runtime.defaults()
            val chat = repository.createSession(null, "Claude live")
            val outcome = runtime.run(
                chat.id,
                "Remember the code word ORCHID-7. Reply with OK only.",
                settings,
                emptyList(),
            ) {}
            assertEquals(StudioRunOutcome.Completed, outcome)
            val firstMessages = repository.observeMessages(chat.id).first { list ->
                list.any { it is StudioMessage.Reply && !it.isStreaming }
            }
            assertTrue(firstMessages.first() is StudioMessage.Prompt, "the prompt leads the transcript: $firstMessages")
            assertTrue(
                firstMessages.filterIsInstance<StudioMessage.Reply>().any { it.text.isNotBlank() },
                "the reply has text: $firstMessages",
            )

            first.closeAndAwaitStorages()
            val second = graph()
            val restored = openProfile(second, connect = false)
            val restoredMessages = restored.studioRepository.observeMessages(chat.id).first { it.isNotEmpty() }
            assertTrue(
                restoredMessages.any { it is StudioMessage.Prompt && "ORCHID-7" in it.text },
                "the transcript survives the restart: $restoredMessages",
            )
            val again = restored.studioRuntime.run(
                chat.id,
                "What was the code word? Reply with the code word only.",
                restored.studioRuntime.defaults(),
                emptyList(),
            ) {}
            assertEquals(StudioRunOutcome.Completed, again)
            val finalMessages = restored.studioRepository.observeMessages(chat.id).first { list ->
                list.count { it is StudioMessage.Reply && !it.isStreaming } >= 2
            }
            assertTrue(
                finalMessages.filterIsInstance<StudioMessage.Reply>().last().text.contains("ORCHID-7"),
                "the continued chat recalls the first turn: $finalMessages",
            )
        }
    }

    private fun graph(): TestAppGraph = createGraphFactory<TestAppGraph.Factory>().create(
        persisted,
    ).also { graphs += it }

    /** Toggles, then (once) the wizard steps of a user: CLI login method, all models, default model. */
    private suspend fun openProfile(app: TestAppGraph, connect: Boolean): AiEngineTestAccessors {
        val toggles = (app as TestToggleAccessors).toggleControl
        toggles.setOverride(AiEngines, true)
        toggles.setOverride(ClaudeEngine.Enabled, true)
        toggles.setOverride(EngineConnectionsEnabled, true)
        toggles.setOverride(StudioEngineRuntime, true)
        val session = app.profileSessions.open(PROFILE)
        val services = session.graph as AiEngineTestAccessors
        if (!connect) return services
        val host = (session.graph as ProfileNavigation).navigation.create(
            DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            listOf(EngineConnectionsRoute(isEmbedded = true)),
        )
        host.navigator.navigate(ConnectEngineRoute(ClaudeEngine.Id))
        val wizard = checkNotNull(app.machines.find(ConnectWizardMachineKey))
        wizard.state.first { it is ConnectWizardState.ChoosingMethod }
        app.machines.send(
            ConnectWizardMachineKey,
            ConnectWizardIntent.Public.Connect(
                ConnectionMethodId("cli"),
                CredentialInput.Existing("Claude Code", EndpointOrigin("https://api.anthropic.com")),
            ),
        )
        val models = wizard.state.first { it is ConnectWizardState.ChoosingModels && it.models != null }
            as ConnectWizardState.ChoosingModels
        app.machines.send(ConnectWizardMachineKey, ConnectWizardIntent.Public.SelectAllModels(true))
        app.machines.send(ConnectWizardMachineKey, ConnectWizardIntent.Public.Finish)
        wizard.state.first { it is ConnectWizardState.Finished }
        val target = EngineTarget(ClaudeEngine.Id, models.connection.binding, ModelId(MODEL))
        checkNotNull(app.machines.find(EngineConnectionsMachineKey)).state.first {
            it is EngineConnectionsState.Active && it.snapshot != null && it.pending == null
        }
        app.machines.send(
            EngineConnectionsMachineKey,
            EngineConnectionsIntent.Public.Apply(ConnectionOperation.SetDefaultModel(target)),
        )
        services.modelSelections.observe().first { it.defaultTarget == target }
        return services
    }

    private companion object {
        val PROFILE = ProfileId("claude-studio")
        const val MODEL = "haiku"
        val SCENARIO_TIMEOUT = 420.seconds
    }
}
