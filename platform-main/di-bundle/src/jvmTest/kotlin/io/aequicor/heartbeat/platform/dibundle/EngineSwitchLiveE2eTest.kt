package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitching
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Opt-in real-provider regression through the production Metro graph, repository and Koog/Pi adapters.
 * Run `:platform-main:di-bundle:engineSwitchLiveTest` with HEARTBEAT_SWITCH_LIVE_KEY_FILE, _ENDPOINT and _MODEL
 * environment variables. The key is read from the file into an isolated profile vault; no user profile is opened.
 * This spends provider quota, never runs in ordinary `check`, and deletes the temporary profile on completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineSwitchLiveE2eTest {
    private val persisted = PersistedProfile()
    private val graphs = mutableListOf<TestAppGraph>()
    private var isEnabled = false

    @BeforeTest
    fun setUp() {
        assumeTrue(System.getProperty("heartbeat.engineSwitch.liveTest") == "true")
        val home = File(checkNotNull(System.getProperty("heartbeat.engineSwitch.liveHome")))
        check(File(System.getProperty("user.home")) == home) { "Live tests require an isolated JVM home" }
        check(home.mkdirs() || home.isDirectory)
        isEnabled = true
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        if (!isEnabled) return
        try {
            runBlocking { graphs.forEach { it.closeAndAwaitStorages() } }
        } finally {
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }

    @Test
    fun `live adapters carry all segments across repeated switches connections and restart`() = runBlocking {
        withTimeout(600.seconds) {
            val first = graph()
            val services = openProfile(first)
            val a = connect(services, KoogEngineId, "First")
            val b = connect(services, PiEngineId, "Second")
            val alternate = connect(services, KoogEngineId, "Alternate")
            services.modelSelections.update { it.withDefault(a).withModel(b, true).withModel(alternate, true) }
            services.studioRepository.observeModels().first { it.size >= 3 }
            val chat = services.studioRepository.createSession(null, "Isolated engine switch regression")
            val alpha = "ORCHID-${UUID.randomUUID()}"
            val beta = "CEDAR-${UUID.randomUUID()}"
            turn(services, chat.id, a, "Remember the first code word $alpha. Reply OK only. Do not use tools.")
            switch(services, chat.id, a, b)
            val fromPi = turn(
                services,
                chat.id,
                b,
                "What was the first code word? Repeat it, then remember the second code word $beta. Do not use tools.",
            )
            assertTrue(alpha in fromPi, "Pi recalls the original Koog segment")
            switch(services, chat.id, b, a)
            switch(services, chat.id, a, b)
            switch(services, chat.id, b, a)
            val fromKoog = turn(services, chat.id, a, "Repeat both remembered code words only. Do not use tools.")
            assertTrue(alpha in fromKoog && beta in fromKoog, "Koog recalls both segments after repeated selection")
            switch(services, chat.id, a, alternate)
            first.closeAndAwaitStorages()
            graphs.remove(first)

            val restored = openProfile(graph())
            val workspace = restored.studioRepository.observeWorkspace().first { it.sessions.any { it.id == chat.id } }
            val restoredChat = workspace.sessions.single { it.id == chat.id }
            assertEquals(chat.title, restoredChat.title)
            assertEquals(Json.encodeToString(EngineTarget.serializer(), alternate), restoredChat.modelId)
            val afterRestart = turn(
                restored,
                chat.id,
                alternate,
                "Repeat both remembered code words only. Do not use tools.",
            )
            assertTrue(
                alpha in afterRestart && beta in afterRestart,
                "A new connection recalls all segments after restart",
            )
            val transcript = restored.studioRepository.observeMessages(chat.id).first { list ->
                list.filterIsInstance<StudioMessage.Reply>().count { !it.isStreaming } == 4
            }
            assertEquals(4, transcript.filterIsInstance<StudioMessage.Prompt>().size)
            assertTrue(transcript.filterIsInstance<StudioMessage.Prompt>().any { alpha in it.text })
            assertTrue(transcript.filterIsInstance<StudioMessage.Prompt>().any { beta in it.text })
        }
    }

    private fun graph(): TestAppGraph = createGraphFactory<TestAppGraph.Factory>().create(persisted).also {
        graphs += it
    }

    private suspend fun openProfile(app: TestAppGraph): AiEngineTestAccessors {
        val toggles = (app as TestToggleAccessors).toggleControl
        val enabled = listOf(
            AiEngines,
            KoogEngineEnabled,
            PiEnabled,
            EngineConnectionsEnabled,
            StudioEngineRuntime,
            StudioEngineSwitching,
        )
        for (toggle in enabled) {
            toggles.setOverride(toggle, true)
        }
        return app.profileSessions.open(ProfileId("engine-switch-live")).graph as AiEngineTestAccessors
    }

    private suspend fun connect(services: AiEngineTestAccessors, engine: EngineId, label: String): EngineTarget {
        val endpoint = URI(checkNotNull(System.getenv("HEARTBEAT_SWITCH_LIVE_ENDPOINT")))
        val scope = AuthScope(
            CompatibleProtocol.OpenAI.provider.id,
            EndpointOrigin("${endpoint.scheme}://${endpoint.rawAuthority}"),
            endpoint.path.trimEnd('/').takeIf { it.isNotEmpty() },
        )
        val keyFile = File(checkNotNull(System.getenv("HEARTBEAT_SWITCH_LIVE_KEY_FILE")))
        val key = Secret(keyFile.readText().trim().toCharArray())
        val source = try {
            services.engineAuthSources.addManagedKey(label, scope, key)
        } finally {
            key.close()
        }
        val facade = services.engineFacade
        facade.engines.state.first { engines -> engines.any { it.descriptor.id == engine } }
        facade.engines.refresh(engine)
        val binding = facade.bindings.connect(engine, source.info.id)
        val model = checkNotNull(System.getenv("HEARTBEAT_SWITCH_LIVE_MODEL"))
        return facade.models.refresh(engine, binding.id).models.first {
            it.target.model.value == model || it.target.model.value == "openai-compatible/$model"
        }.target
    }

    private suspend fun switch(services: AiEngineTestAccessors, chat: String, from: EngineTarget, to: EngineTarget) {
        val before = services.studioRepository.observeMessages(chat).first().filterIsInstance<StudioMessage.Prompt>()
        services.studioRuntime.switchEngine(chat, StudioEngineSwitch(from, to))
        val state = services.studioRuntime.state.value
        assertNull(state.engineSwitches[chat]?.failure, "Switch ${from.engine} to ${to.engine}")
        assertEquals(Json.encodeToString(EngineTarget.serializer(), to), state.configurations[chat]?.applied?.modelId)
        assertTrue(chat !in state.running)
        val after = services.studioRepository.observeMessages(chat).first().filterIsInstance<StudioMessage.Prompt>()
        assertEquals(
            before.map { it.copy(id = "") },
            after.map { it.copy(id = "") },
            "Selecting an engine only qualifies frozen message IDs; it never sends a prompt",
        )
    }

    private suspend fun turn(
        services: AiEngineTestAccessors,
        chat: String,
        target: EngineTarget,
        prompt: String,
    ): String {
        val completed = services.studioRepository.observeMessages(chat).first()
            .filterIsInstance<StudioMessage.Reply>().count { !it.isStreaming }
        val settings = services.studioRuntime.defaults().copy(
            modelId = Json.encodeToString(EngineTarget.serializer(), target),
        )
        val outcome = services.studioRuntime.run(chat, prompt, settings, emptyList()) {}
        assertEquals(RunOutcome.Completed, outcome, "Live turn on ${target.engine}")
        return services.studioRepository.observeMessages(chat).first { list ->
            list.filterIsInstance<StudioMessage.Reply>().count { !it.isStreaming } == completed + 1
        }.filterIsInstance<StudioMessage.Reply>().last().text
    }
}
