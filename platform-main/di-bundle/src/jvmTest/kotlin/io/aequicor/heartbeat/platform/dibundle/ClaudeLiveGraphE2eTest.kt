package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
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
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Profile accessor of the CLI login probe, which the wizard's model discovery relies on. */
@ContributesTo(ProfileScope::class)
interface ClaudeAuthenticationAccessor {
    val claudeAuthentication: ClaudeAuthentication
}

/**
 * Opt-in regression of the production composition with the real Claude Code CLI, outside `check`:
 * `./gradlew :platform-main:di-bundle:claudeLiveGraphTest`. The steps are the ones of the connection wizard and of a
 * studio chat: toggle, installation probe, CLI login source, binding, model discovery, session through the facade,
 * the unified session index, and a restart of the whole graph over the same storage. The CLI is found the way the
 * app finds it with no settings: PATH, the native installer, then the Claude Code desktop app and its login.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeLiveGraphE2eTest {
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
    fun `the wizard flow connects the desktop login and a chat survives a restart of the graph`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val first = openProfile()
            val engines = first.facade.engines
            assertTrue(engines.state.value.any { it.descriptor.id == ClaudeEngine.Id }, "Claude is registered")
            val info = engines.refresh(ClaudeEngine.Id)
            observe("probe: ${info.availability}")
            assertEquals(EngineAvailability.Available, info.availability, "installation probe: $info")

            val source = first.sources.create(info.descriptor.connectionMethods.single(), "Claude Code")
            val binding = first.facade.bindings.connect(ClaudeEngine.Id, source.info.id)
            observe("connected binding=${binding.id.value} source=${source.info.id.value}")
            val target = EngineTarget(ClaudeEngine.Id, binding.id, ModelId(MODEL))
            val login = first.claudeAuthentication.inspect()
            assertEquals(AuthVerdict.Authenticated, login.check.verdict, "the CLI login: ${login.check}")
            val models = first.facade.models.refresh(ClaudeEngine.Id, binding.id).models
            observe("models: ${models.map { it.target.model.value }}")
            assertTrue(models.any { it.target.model.value == MODEL }, "the model list has $MODEL: $models")

            val creates = engines.features(ClaudeEngine.Id).available(CreatesSessions)
            observe("creating a session")
            val chat = creates.create(CreateSessionRequest(target))
            observe("created ${chat.ref}")
            chat.finished(chat.say("Remember the code word ORCHID-7. Reply with OK only."))
            val indexed = first.facade.sessions.page().items
            assertTrue(indexed.any { it.ref == chat.ref }, "the new chat is in the unified index: $indexed")
            val ref = chat.ref
            chat.close()

            // "Process death": the same storage, a new graph, a new profile session.
            first.close()
            val second = openProfile()
            val restored = second.facade.sessions.page().items.singleOrNull { it.ref == ref }
            assertTrue(restored != null, "the chat is still in the index after the restart")
            val reopened = second.facade.sessions.get(ref).features.available(ResumesSessions)
                .resume(ResumeSessionRequest(target))
            assertTranscriptHas(reopened, "ORCHID-7")
            reopened.finished(reopened.say("What was the code word? Reply with the code word only."))
            assertTrue(
                reopened.transcript().last { it.first == MessageRole.Assistant }.second.contains("ORCHID-7"),
                "the resumed native conversation recalls the first turn",
            )
            reopened.close()
        }
    }

    /** Observations for the run report: the file named by `heartbeat.claude.liveReport`, when set. */
    private fun observe(line: String) {
        System.getProperty("heartbeat.claude.liveReport")?.takeIf(String::isNotBlank)
            ?.let { File(it).appendText("${System.currentTimeMillis()} graph: $line\n") }
    }

    private suspend fun assertTranscriptHas(session: ActiveSession, text: String) {
        val transcript = session.transcript()
        assertTrue(transcript.any { text in it.second }, "the restored history shows $text: $transcript")
    }

    private suspend fun openProfile(): LiveProfile {
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        graphs += app
        (app as TestToggleAccessors).toggleControl.setOverride(ClaudeEngine.Enabled, true)
        val session = app.profileSessions.open(PROFILE)
        val services = (session.graph as EngineServicesAccessor).engineServices
        return LiveProfile(services, (session.graph as ClaudeAuthenticationAccessor).claudeAuthentication, app)
    }

    private class LiveProfile(
        private val services: EngineServices,
        val claudeAuthentication: ClaudeAuthentication,
        private val app: TestAppGraph,
    ) {
        val facade get() = services.facade
        val sources get() = services.sources

        suspend fun close() {
            app.closeAndAwaitStorages()
        }
    }

    private suspend fun ActiveSession.say(text: String): TurnId = features.available(SendsPrompts).send(
        PromptRequest(RequestId(UUID.randomUUID().toString()), listOf(ContentPart.Text(text))),
    )

    private suspend fun ActiveSession.finished(turn: TurnId): TurnOutcome? = withTimeout(TURN_TIMEOUT) {
        val ready = state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == turn }
        val outcome = assertIs<ActiveSessionState.Ready>(ready).lastTurn?.outcome
        assertEquals(TurnOutcome.Completed, outcome)
        outcome
    }

    private suspend fun ActiveSession.transcript(): List<Pair<MessageRole, String>> = features.available(SessionHistory)
        .page(HistoryPageRequest(limit = 200))
        .items
        .filterIsInstance<SessionItem.Message>()
        .map { message ->
            message.role to message.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        }

    private fun <F : EngineFeature> EngineFeatures.available(key: EngineFeatureKey<F>): F =
        assertIs<FeatureAccess.Available<F>>(resolve(key)).feature

    private companion object {
        val PROFILE = ProfileId("claude-live")
        const val MODEL = "haiku"
        val TURN_TIMEOUT = 180.seconds
        val SCENARIO_TIMEOUT = 420.seconds
    }
}
