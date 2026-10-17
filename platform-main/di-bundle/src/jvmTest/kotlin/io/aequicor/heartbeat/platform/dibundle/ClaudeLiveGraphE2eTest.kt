package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.milliseconds
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
            val target = first.connect()
            val engines = first.facade.engines
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

    @Test
    fun `hosted tools of the profile reach a Claude session through the MCP bridge`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile(SchedulerEnabled)
            val target = profile.connect()
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target, areDetachedToolsEnabled = true))
            val turn = chat.say("Call the tool scheduler_list exactly once, then reply with exactly the word LISTED.")
            chat.finished(turn, allowAsking = true)
            val items = chat.features.available(SessionHistory).page(HistoryPageRequest(limit = 200)).items
            observe(
                "hosted tool items: ${items.map { it::class.simpleName + ":" + (it as? SessionItem.ToolCall)?.name }}",
            )
            assertTrue(
                items.any { it is SessionItem.ToolCall && "scheduler_list" in it.name },
                "the model called the hosted tool: $items",
            )
            assertTrue(
                items.any { it is SessionItem.ToolResult && it.failure == null },
                "the hosted tool answered without a failure: $items",
            )
            chat.close()
        }
    }

    @Test
    fun `disconnecting the binding stops a running turn and its process`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target))
            chat.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
            chat.state.first { it is ActiveSessionState.Running }
            profile.facade.bindings.disconnect(target.binding)
            assertTrue(profile.facade.bindings.state.value.none { it.id == target.binding }, "the binding is gone")
            var attempts = 0
            while (claudeProcesses().isNotEmpty() && attempts++ < PROCESS_EXIT_POLLS) delay(PROCESS_EXIT_POLL)
            assertTrue(claudeProcesses().isEmpty(), "the CLI process stopped with its binding: ${claudeProcesses()}")
        }
    }

    @Test
    fun `a project session writes under the Ask level after approval and under Full without asking`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val directory = File(persisted.storageRoot, "project").apply { mkdirs() }.canonicalFile
            val workspace = profile.workspaces.registerManaged(directory.path).ref
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target, workspace))

            val asked = chat.say(
                "Create the file asked.txt in the current directory with the text ASKED, using the Write tool.",
                trust = TrustLevel.Ask,
            )
            val sawRequest = chat.finished(asked, allowAsking = true)
            observe("ask level outcome=$sawRequest permissions=$permissionsSeen")
            assertTrue(permissionsSeen > 0, "the Ask level asked before writing")
            assertEquals("ASKED", File(directory, "asked.txt").readText().trim())

            permissionsSeen = 0
            val free = chat.say(
                "Create the file free.txt in the current directory with the text FREE, using the Write tool.",
                trust = TrustLevel.Full,
            )
            chat.finished(free, allowAsking = true)
            observe("full level permissions=$permissionsSeen")
            assertEquals(0, permissionsSeen, "the Full level does not ask")
            assertEquals("FREE", File(directory, "free.txt").readText().trim())
            chat.close()
        }
    }

    @Test
    fun `automatic review lets a safe project write through and refuses one outside the project`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            observe("auto: profile open")
            val target = profile.connect()
            observe("auto: connected")
            val directory = File(persisted.storageRoot, "reviewed").apply { mkdirs() }.canonicalFile
            val outside = File(persisted.storageRoot, "outside.txt")
            val workspace = profile.workspaces.registerManaged(directory.path).ref
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target, workspace))
            observe("auto: session created")

            val safe = chat.say(
                "Create the file safe.txt in the current directory with the text SAFE, using the Write tool.",
                decisionMode = AgentToolDecisionMode.AutomaticSafety,
            )
            observe("auto: safe write sent")
            chat.finished(safe)
            observe("auto: safe write finished")
            assertEquals("SAFE", File(directory, "safe.txt").readText().trim())

            val escaped = chat.say(
                "Create the file ${outside.path} with the text OUTSIDE, using the Write tool. " +
                    "If it is refused, reply REFUSED.",
                decisionMode = AgentToolDecisionMode.AutomaticSafety,
            )
            observe("auto: outside write sent")
            chat.finished(escaped)
            observe("auto: outside write finished")
            assertTrue(!outside.exists(), "a write outside the project is refused without a dialog")
            chat.close()
        }
    }

    @Test
    fun `switching the toggle off hides the engine and refuses its probes`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val engines = profile.facade.engines
            // The catalog is a state flow that fills after the profile opens and after each toggle change.
            engines.state.first { list -> list.any { it.descriptor.id == ClaudeEngine.Id } }
            (graphs.last() as TestToggleAccessors).toggleControl.setOverride(ClaudeEngine.Enabled, false)
            engines.state.first { list -> list.none { it.descriptor.id == ClaudeEngine.Id } }
            val failure = try {
                engines.refresh(ClaudeEngine.Id)
                null
            } catch (e: EngineException) {
                e.failure
            }
            assertTrue(failure != null, "a disabled engine refuses its probe")
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

    private fun claudeProcesses() = ProcessHandle.current().descendants()
        .filter { it.isAlive && it.info().command().orElse("").contains("claude") }
        .toList()

    private suspend fun openProfile(vararg extra: FeatureToggle.Flag): LiveProfile {
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        graphs += app
        val toggles = (app as TestToggleAccessors).toggleControl
        toggles.setOverride(ClaudeEngine.Enabled, true)
        extra.forEach { toggles.setOverride(it, true) }
        val session = app.profileSessions.open(PROFILE)
        val services = (session.graph as EngineServicesAccessor).engineServices
        return LiveProfile(
            services,
            (session.graph as ClaudeAuthenticationAccessor).claudeAuthentication,
            (session.graph as AiEngineTestAccessors).localWorkspaces,
            app,
        )
    }

    private class LiveProfile(
        private val services: EngineServices,
        val claudeAuthentication: ClaudeAuthentication,
        val workspaces: LocalWorkspaces,
        private val app: TestAppGraph,
    ) {
        val facade get() = services.facade
        val sources get() = services.sources

        suspend fun close() {
            app.closeAndAwaitStorages()
        }

        /** The wizard's steps: probe, CLI login source, binding, the login check and model discovery. */
        suspend fun connect(): EngineTarget {
            val info = facade.engines.refresh(ClaudeEngine.Id)
            assertEquals(EngineAvailability.Available, info.availability, "installation probe: $info")
            val source = sources.create(info.descriptor.connectionMethods.single(), "Claude Code")
            val binding = facade.bindings.connect(ClaudeEngine.Id, source.info.id)
            val login = claudeAuthentication.inspect()
            assertEquals(AuthVerdict.Authenticated, login.check.verdict, "the CLI login: ${login.check}")
            val models = facade.models.refresh(ClaudeEngine.Id, binding.id).models
            assertTrue(models.any { it.target.model.value == MODEL }, "the model list has $MODEL: $models")
            return EngineTarget(ClaudeEngine.Id, binding.id, ModelId(MODEL))
        }
    }

    private var permissionsSeen = 0

    private suspend fun ActiveSession.say(
        text: String,
        trust: TrustLevel? = null,
        decisionMode: AgentToolDecisionMode = AgentToolDecisionMode.Interactive,
    ): TurnId = features.available(SendsPrompts).send(
        PromptRequest(
            RequestId(UUID.randomUUID().toString()),
            listOf(ContentPart.Text(text)),
            trust = trust,
            decisionMode = decisionMode,
        ),
    )

    /** Waits for [turn] to complete; with [allowAsking] every permission request of the turn is allowed. */
    private suspend fun ActiveSession.finished(turn: TurnId, allowAsking: Boolean = false): TurnOutcome? =
        withTimeout(TURN_TIMEOUT) {
            var outcome: TurnOutcome? = null
            var isDone = false
            while (!isDone) {
                val current = state.first {
                    (allowAsking && it is ActiveSessionState.AwaitingUserAction) ||
                        (it is ActiveSessionState.Ready && it.lastTurn?.id == turn)
                }
                if (current is ActiveSessionState.AwaitingUserAction) {
                    val request = current.requests.first()
                    observe("permission: ${request.title}")
                    permissionsSeen++
                    features.available(RequestsPermissions)
                        .respond(PermissionDecision(turn, request.id, request.options.first().id))
                } else {
                    outcome = assertIs<ActiveSessionState.Ready>(current).lastTurn?.outcome
                    isDone = true
                }
            }
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
        val PROCESS_EXIT_POLL = 500.milliseconds
        const val PROCESS_EXIT_POLLS = 20
    }
}
