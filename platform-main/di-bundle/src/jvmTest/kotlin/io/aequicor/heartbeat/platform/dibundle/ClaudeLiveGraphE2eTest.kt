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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertFailsWith
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
            assertTrue(awaitClaudeProcesses().isNotEmpty(), "a CLI process was running when the binding went away")
            profile.facade.bindings.disconnect(target.binding)
            assertTrue(profile.facade.bindings.state.value.none { it.id == target.binding }, "the binding is gone")
            var attempts = 0
            while (claudeProcesses().isNotEmpty() && attempts++ < PROCESS_EXIT_POLLS) delay(PROCESS_EXIT_POLL)
            assertTrue(claudeProcesses().isEmpty(), "the CLI process stopped with its binding: ${claudeProcesses()}")
        }
    }

    @Test
    fun `profile Ask and Trust override stale turn trust`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val directory = File(persisted.storageRoot, "project").apply { mkdirs() }.canonicalFile
            val workspace = profile.workspaces.registerManaged(directory.path).ref
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target, workspace))

            profile.security.save(SecurityMode.Ask)
            val asked = chat.say(
                "Create the file asked.txt in the current directory with the text ASKED, using the Write tool.",
                trust = TrustLevel.Full,
            )
            val sawRequest = chat.finished(asked, allowAsking = true)
            observe("ask level outcome=$sawRequest permissions=$permissionsSeen")
            assertTrue(permissionsSeen > 0, "the Ask level asked before writing")
            assertEquals("ASKED", File(directory, "asked.txt").readText().trim())

            permissionsSeen = 0
            profile.security.save(SecurityMode.Trust)
            val free = chat.say(
                "Create the file free.txt in the current directory with the text FREE, using the Write tool.",
                trust = TrustLevel.Ask,
            )
            chat.finished(free, allowAsking = true)
            observe("full level permissions=$permissionsSeen")
            assertEquals(0, permissionsSeen, "the Full level does not ask")
            assertEquals("FREE", File(directory, "free.txt").readText().trim())
            chat.close()
        }
    }

    @Test
    fun `Automatic asks for native writes and an explicit denial prevents the outside write`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            profile.security.save(SecurityMode.Automatic)
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
                trust = TrustLevel.Full,
            )
            observe("auto: safe write sent")
            chat.finished(safe, allowAsking = true)
            assertTrue(permissionsSeen > 0, "a native Write requires confirmation in Automatic")
            assertTrue(
                chat.features.available(SessionHistory).page(HistoryPageRequest(limit = 200)).items
                    .any { it is SessionItem.ToolCall && it.name == "Write" },
                "the model used the native Write tool",
            )
            observe("auto: safe write finished")
            assertEquals("SAFE", File(directory, "safe.txt").readText().trim())

            permissionsSeen = 0
            val escaped = chat.say(
                "Create the file ${outside.path} with the text OUTSIDE, using the Write tool. " +
                    "If it is refused, reply REFUSED.",
                trust = TrustLevel.Full,
            )
            observe("auto: outside write sent")
            chat.finished(escaped, allowAsking = true, permission = PermissionOptionId("deny"))
            observe("auto: outside write finished")
            assertTrue(permissionsSeen > 0, "the outside write reaches user confirmation")
            assertTrue(!outside.exists(), "an explicit denial prevents the outside write")
            chat.close()
        }
    }

    @Test
    fun `engine management shows the desktop installation with its login and applies launch settings`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile(EngineManagementEnabled)
            val management = profile.management
            fun EngineManagementState.claude() = engines.single { it.descriptor.id == ClaudeEngine.Id }
            management.state.first {
                it.isEnabled &&
                    it.engines.any { engine -> engine.descriptor.id == ClaudeEngine.Id }
            }

            // Inspection only reads the installation and the CLI login; nothing is installed or signed in.
            management.execute(ClaudeEngine.Id, EngineCommand.Inspect)
            val inspected = management.state.first { it.claude().installation.current != null }.claude()
            observe("inspected: ${inspected.installation.current} login=${inspected.login}")
            assertEquals(InstallSource.System, inspected.installation.current?.source)
            assertTrue(!inspected.installation.current?.version.isNullOrBlank(), "the CLI version is known")
            assertIs<LoginState.SignedIn>(inspected.login, "the desktop login is seen: ${inspected.login}")

            management.execute(ClaudeEngine.Id, EngineCommand.CheckForUpdates)
            val checked = management.state.first { it.claude().installation.checkedAt != null }.claude()
            observe("update check: latest=${checked.installation.latest} failure=${checked.installation.failure}")
            assertTrue(checked.installation.failure == null, "the update check works: ${checked.installation.failure}")
            assertTrue(!checked.installation.latest.isNullOrBlank(), "the newest release is known")

            val missing = File(persisted.storageRoot, "no-such-claude").path
            management.execute(ClaudeEngine.Id, EngineCommand.Configure(LaunchSettings(executable = missing)))
            val configured = management.state.first { it.claude().launch.settings.executable == missing }.claude()
            assertTrue(
                configured.launch.warnings.any { it.option == LaunchOption.Executable },
                "a missing executable is flagged: ${configured.launch.warnings}",
            )
            val unavailable = profile.facade.engines.refresh(ClaudeEngine.Id).availability
            assertIs<EngineAvailability.Unavailable>(unavailable, "a missing custom executable is not started")

            management.execute(ClaudeEngine.Id, EngineCommand.Configure(LaunchSettings()))
            management.state.first { it.claude().launch.settings == LaunchSettings() }
            assertEquals(EngineAvailability.Available, profile.facade.engines.refresh(ClaudeEngine.Id).availability)

            management.execute(ClaudeEngine.Id, EngineCommand.SetEnabled(false))
            management.state.first { !it.claude().enablement.isEnabled }
            management.execute(ClaudeEngine.Id, EngineCommand.SetEnabled(true))
            assertTrue(management.state.first { it.claude().enablement.isEnabled }.isEnabled)
        }
    }

    @Test
    fun `closing the app stops a running turn and leaves no CLI process`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target))
            chat.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
            chat.state.first { it is ActiveSessionState.Running }
            assertTrue(awaitClaudeProcesses().isNotEmpty(), "a CLI process was running when the app closed")
            profile.close()
            var attempts = 0
            while (claudeProcesses().isNotEmpty() && attempts++ < PROCESS_EXIT_POLLS) delay(PROCESS_EXIT_POLL)
            assertTrue(claudeProcesses().isEmpty(), "the CLI stopped with the app: ${claudeProcesses()}")
        }
    }

    @Test
    fun `a CLI home without a login is reported as signed out and the engine recovers with the default home`() =
        runBlocking {
            withTimeout(SCENARIO_TIMEOUT) {
                val profile = openProfile(EngineManagementEnabled)
                val target = profile.connect()
                val emptyHome = File(persisted.storageRoot, "empty-claude-home").apply { mkdirs() }.path
                profile.management.execute(
                    ClaudeEngine.Id,
                    EngineCommand.Configure(LaunchSettings(homeDirectory = emptyHome)),
                )
                val login = profile.claudeAuthentication.inspect()
                observe("signed-out login: ${login.check.verdict}")
                assertEquals(AuthVerdict.NeedsLogin, login.check.verdict, "an empty home has no login: ${login.check}")
                val refused = assertFailsWith<EngineException>("model discovery refuses a signed-out CLI") {
                    profile.facade.models.refresh(ClaudeEngine.Id, target.binding)
                }
                observe("signed-out model discovery: ${refused.failure}")

                profile.management.execute(ClaudeEngine.Id, EngineCommand.Configure(LaunchSettings()))
                assertEquals(AuthVerdict.Authenticated, profile.claudeAuthentication.inspect().check.verdict)
                assertTrue(profile.facade.models.refresh(ClaudeEngine.Id, target.binding).models.isNotEmpty())
            }
        }

    @Test
    fun `a CLI killed while its turn runs leaves a handle its owner reconciles and keeps working`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target))
            val turn = chat.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
            chat.state.first { it is ActiveSessionState.Running }
            // The turn is accepted on the CLI's first frames, so this is a lost turn; a death before any frame is
            // covered by the unit tests of the runtime, where the transport can end without one.
            var killed = emptyList<ProcessHandle>()
            var attempts = 0
            while (killed.isEmpty() && attempts++ < KILL_POLLS) {
                delay(PROCESS_EXIT_POLL)
                killed = claudeProcesses().onEach { it.destroyForcibly() }
            }
            assertTrue(killed.isNotEmpty(), "a CLI process was running to kill")
            // The turn's outcome is unknown, so the handle waits for the owner to reconcile it, as a screen does.
            withTimeout(RECONCILE_TIMEOUT) { chat.state.first { it is ActiveSessionState.Unavailable } }
            chat.features.available(ReconcilesSession).synchronize()
            val ready = withTimeout(RECONCILE_TIMEOUT) { chat.state.first { it is ActiveSessionState.Ready } }
            observe("facade handle reconciled: ${assertIs<ActiveSessionState.Ready>(ready).lastTurn?.outcome}")
            assertTrue(assertIs<ActiveSessionState.Ready>(ready).lastTurn?.id == turn)
            val next = sayWhenIdle(chat, "Reply with exactly the single word READY.")
            chat.finished(next)
            assertTrue(
                chat.transcript().last { it.first == MessageRole.Assistant }.second.uppercase().contains("READY"),
            )
            chat.close()
        }
    }

    @Test
    fun `closing handles one after another never fails because the scope ended first`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val creates = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
            repeat(CLOSE_ROUNDS) {
                creates.create(CreateSessionRequest(target)).close()
            }
        }
    }

    @Test
    fun `two chats run turns at the same time without mixing their conversations`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val creates = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
            val first = creates.create(CreateSessionRequest(target))
            val second = creates.create(CreateSessionRequest(target))
            val turns = coroutineScope {
                listOf(
                    async { first to first.say("Remember the code word AMBER-1. Reply with OK only.") },
                    async { second to second.say("Remember the code word COBALT-2. Reply with OK only.") },
                ).awaitAll()
            }
            turns.forEach { (chat, turn) -> chat.finished(turn) }
            val askFirst = first.say("What was the code word? Reply with the code word only.")
            val askSecond = second.say("What was the code word? Reply with the code word only.")
            first.finished(askFirst)
            second.finished(askSecond)
            val firstAnswer = first.transcript().last { it.first == MessageRole.Assistant }.second
            val secondAnswer = second.transcript().last { it.first == MessageRole.Assistant }.second
            assertTrue("AMBER-1" in firstAnswer && "COBALT-2" !in firstAnswer, "first chat: $firstAnswer")
            assertTrue("COBALT-2" in secondAnswer && "AMBER-1" !in secondAnswer, "second chat: $secondAnswer")
            first.close()
            second.close()
        }
    }

    @Test
    fun `context occupancy and the account limits are reported after a turn`() = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) {
            val profile = openProfile()
            val target = profile.connect()
            val chat = profile.facade.engines.features(ClaudeEngine.Id).available(CreatesSessions)
                .create(CreateSessionRequest(target))
            chat.finished(chat.say("Reply with exactly the single word PONG."))
            val usage = chat.features.available(SessionContextUsage)
            val context = withTimeout(RECONCILE_TIMEOUT) { usage.state.first { it?.capacityTokens != null } }
            observe("context usage: $context")
            assertTrue(context != null && context.usedTokens > 0)
            assertTrue(requireNotNull(context.capacityTokens) >= context.usedTokens)
            val limits = profile.facade.providerUsage.refresh(ClaudeEngine.Id, target.binding)
            observe("provider usage: windows=${limits.windows.map { it.id }} plan=${limits.planName}")
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
            val refused = assertFailsWith<EngineException>("a disabled engine refuses its probe") {
                engines.refresh(ClaudeEngine.Id)
            }
            observe("disabled engine probe: ${refused.failure}")
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

    /** The CLI processes of a running turn; the process may appear a moment after the state flips. */
    private suspend fun awaitClaudeProcesses(): List<ProcessHandle> {
        var running = claudeProcesses()
        var attempts = 0
        while (running.isEmpty() && attempts++ < KILL_POLLS) {
            delay(PROCESS_EXIT_POLL)
            running = claudeProcesses()
        }
        return running
    }

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
            (session.graph as AiEngineTestAccessors).engineManagement,
            (session.graph as AiEngineTestAccessors).agentSecuritySettings,
            app,
        )
    }

    private class LiveProfile(
        private val services: EngineServices,
        val claudeAuthentication: ClaudeAuthentication,
        val workspaces: LocalWorkspaces,
        val management: EngineManagement,
        val security: AgentSecuritySettings,
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

    /**
     * Sends right after a reconciliation: the facade may still be finishing a second pass of it and answers Busy
     * for a moment, so the prompt is retried for a bounded time.
     */
    private suspend fun sayWhenIdle(session: ActiveSession, text: String): TurnId {
        var attempts = 0
        while (true) {
            try {
                return session.say(text)
            } catch (e: EngineException) {
                if (e.failure != EngineFailure.Session(SessionFailureReason.Busy) || attempts++ >= BUSY_RETRIES) throw e
                observe("send right after the reconciliation was busy (attempt $attempts)")
                delay(PROCESS_EXIT_POLL)
            }
        }
    }

    private suspend fun ActiveSession.say(text: String, trust: TrustLevel? = null): TurnId =
        features.available(SendsPrompts).send(
            PromptRequest(
                RequestId(UUID.randomUUID().toString()),
                listOf(ContentPart.Text(text)),
                trust = trust,
            ),
        )

    /** Waits for [turn]; with [allowAsking] each permission request receives the explicit [permission] choice. */
    private suspend fun ActiveSession.finished(
        turn: TurnId,
        allowAsking: Boolean = false,
        permission: PermissionOptionId = PermissionOptionId("allow"),
    ): TurnOutcome? = withTimeout(TURN_TIMEOUT) {
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
                assertTrue(request.options.any { it.id == permission }, "the requested choice is available")
                features.available(RequestsPermissions)
                    .respond(PermissionDecision(turn, request.id, permission))
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
        const val KILL_POLLS = 40
        const val CLOSE_ROUNDS = 60
        const val BUSY_RETRIES = 10
        val RECONCILE_TIMEOUT = 30.seconds
    }
}
