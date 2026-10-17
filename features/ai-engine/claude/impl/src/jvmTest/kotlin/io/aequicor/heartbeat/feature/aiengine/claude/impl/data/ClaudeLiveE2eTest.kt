package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeConfirmation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeCallClassifier
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeAttachment
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Opt-in regression against the real Claude Code CLI, outside `check` (it needs an authenticated CLI and spends
 * model quota): `./gradlew :features:ai-engine:claude:impl:claudeLiveTest`. The CLI is discovered like the app does it
 * (PATH, the native installer, the Claude Code desktop app) or taken from the `-Pheartbeat.claude.executable=<path>`
 * Gradle property; the login is the one of that CLI, nothing is signed in or out. Every scenario drives the
 * production runtime, transport and account probe, only the profile scope, catalog and toggles are in-memory; the
 * regular `jvmTest` skips the class.
 */
class ClaudeLiveE2eTest {
    private val log = Log.tag("ClaudeLiveE2e")
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private lateinit var workspaceDirectory: Path

    @BeforeTest
    fun prepare() {
        assumeTrue(System.getProperty("heartbeat.claude.liveTest") == "true")
        workspaceDirectory = Files.createTempDirectory("heartbeat-claude-live").toRealPath()
    }

    @AfterTest
    fun cleanup() {
        scope.cancel()
        if (::workspaceDirectory.isInitialized) workspaceDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `installation is found and the login is authenticated`() = live { fixture ->
        val backend = fixture.backend()
        assertEquals(EngineAvailability.Available, backend.checkRequirements())
        assertTrue(fixture.transport.version().orEmpty().isNotBlank(), "claude --version prints a version")
        val login = fixture.account.inspect()
        assertEquals(AuthVerdict.Authenticated, login.check.verdict)
    }

    @Test
    fun `without configuration the Claude Code desktop CLI is discovered and its login is used`() = live { fixture ->
        assumeTrue(System.getProperty("heartbeat.claude.executable").isNullOrBlank())
        val installation = fixture.transport.locate(LaunchContext())
        observe("discovered installation: $installation path=${installation.path}")
        assertEquals(InstallSource.System, installation.source)
        assertTrue(installation.isRunnable && !installation.version.isNullOrBlank(), "found: $installation")
        assertEquals(AuthVerdict.Authenticated, fixture.account.inspect().check.verdict)
    }

    @Test
    fun `model discovery lists the models of the login with their efforts`() = live { fixture ->
        val login = fixture.account.inspect()
        val models = fixture.backend().discoverModels(login.source, EngineContext(ClaudeEngine.Id, BINDING))
        assertTrue(models.isNotEmpty(), "the CLI advertises models")
        observe("models: " + models.joinToString { "${it.target.model.value}${it.reasoningEfforts}" })
    }

    @Test
    fun `a plain turn completes and the transcript holds both messages`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val turn = session.say("Reply with exactly the single word PONG and nothing else.")
        val outcome = session.finished(turn)
        assertEquals(TurnOutcome.Completed, outcome)
        val transcript = session.transcript()
        assertTrue(transcript.any { it.role == MessageRole.User }, "user message is in the transcript: $transcript")
        assertTrue(
            transcript.filter { it.role == MessageRole.Assistant }.any { "PONG" in it.text.uppercase() },
            "assistant answer is in the transcript: $transcript",
        )
        runtime.close()
    }

    @Test
    fun `a second turn of the same session remembers the first`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        session.finished(session.say("Remember the code word ORCHID-7. Reply with OK only."))
        val turn = session.say("What was the code word? Reply with the code word only.")
        assertEquals(TurnOutcome.Completed, session.finished(turn))
        assertTrue(
            session.transcript().last { it.role == MessageRole.Assistant }.text.contains("ORCHID-7"),
            "second answer recalls the first turn",
        )
        runtime.close()
    }

    @Test
    fun `a cancelled turn ends as cancelled and the session keeps working`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val turn = session.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
        session.state.first { it is ActiveSessionState.Running }
        session.features.available(CancelsTurns).cancel(turn)
        val outcome = session.finished(turn)
        assertEquals(TurnOutcome.Cancelled, outcome)
        val next = session.say("Reply with exactly the single word READY.")
        assertEquals(TurnOutcome.Completed, session.finished(next))
        assertTrue(session.transcript().last { it.role == MessageRole.Assistant }.text.uppercase().contains("READY"))
        runtime.close()
    }

    @Test
    fun `a session reopened by another runtime continues the native conversation`() = live { fixture ->
        val first = fixture.runtime()
        val created = first.create(CreateSessionRequest(target()))
        created.finished(created.say("Remember the code word LANTERN-3. Reply with OK only."))
        val ref = created.ref
        created.close()
        first.close()

        val second = fixture.runtime()
        val reopened = second.attach(ref, ResumeSessionRequest(target()))
        val turn = reopened.say("What was the code word? Reply with the code word only.")
        assertEquals(TurnOutcome.Completed, reopened.finished(turn))
        assertTrue(
            reopened.transcript().last { it.role == MessageRole.Assistant }.text.contains("LANTERN-3"),
            "the reopened session recalls the first runtime's turn",
        )
        second.close()
    }

    @Test
    fun `a supported effort override is accepted by the cli`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val turn = session.say("Reply with exactly the single word PONG.", effort = "low")
        assertEquals(TurnOutcome.Completed, session.finished(turn))
        runtime.close()
    }

    @Test
    fun `a write in the workspace asks for permission and runs after approval`() = live { fixture ->
        val runtime = fixture.runtime(fixture.askingTools(), fixture.nativeSupport())
        val session = runtime.create(CreateSessionRequest(target(), workspace = WORKSPACE))
        val turn = session.say(
            "Create the file hello.txt in the current directory containing exactly the text HI. " +
                "Use the Write tool, then reply DONE.",
        )
        val finished = session.approveUntilFinished(turn, allow = true)
        assertEquals(TurnOutcome.Completed, finished)
        assertEquals("HI", workspaceDirectory.resolve("hello.txt").toFile().readText().trim())
        runtime.close()
    }

    @Test
    fun `a denied write leaves the workspace untouched and the turn ends`() = live { fixture ->
        val runtime = fixture.runtime(fixture.askingTools(), fixture.nativeSupport())
        val session = runtime.create(CreateSessionRequest(target(), workspace = WORKSPACE))
        val turn = session.say(
            "Create the file denied.txt in the current directory containing exactly the text NO. " +
                "Use the Write tool. If it is refused, reply REFUSED.",
        )
        val finished = session.approveUntilFinished(turn, allow = false)
        assertTrue(finished == TurnOutcome.Completed || finished == TurnOutcome.Cancelled, "outcome $finished")
        assertTrue(!workspaceDirectory.resolve("denied.txt").toFile().exists(), "the denied file must not exist")
        runtime.close()
    }

    @Test
    fun `an unknown model fails the turn with a model access failure instead of hanging`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target("no-such-model-xyz")))
        val turn = session.say("Reply PONG.")
        val ready = withTimeout(TURN_TIMEOUT) {
            session.state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == turn }
        }
        val outcome = assertIs<ActiveSessionState.Ready>(ready).lastTurn?.outcome
        observe("unknown model outcome: $outcome")
        assertEquals(TurnOutcome.Failed(EngineFailure.Access(AccessFailureReason.ModelAccessDenied)), outcome)
        runtime.close()
    }

    @Test
    fun `a missing executable is reported as unavailable`() = live { fixture ->
        val backend = fixture.backend(executable = workspaceDirectory.resolve("no-such-claude").toString())
        val availability = backend.checkRequirements()
        assertIs<EngineAvailability.Unavailable>(availability)
    }

    @Test
    fun `an approved command runs in the workspace and its output reaches the answer`() = live { fixture ->
        val runtime = fixture.runtime(fixture.askingTools(), fixture.nativeSupport())
        val session = runtime.create(CreateSessionRequest(target(), workspace = WORKSPACE))
        val turn = session.say("Run the shell command: echo live-e2e-$((6*7)) and reply with its output only.")
        assertEquals(TurnOutcome.Completed, session.approveUntilFinished(turn, allow = true))
        assertTrue(
            session.transcript().last { it.role == MessageRole.Assistant }.text.contains("live-e2e-42"),
            "the command output is in the answer: ${session.transcript()}",
        )
        runtime.close()
    }

    @Test
    fun `cyrillic quotes json and emoji in a prompt reach the model unchanged`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val payload = "Ответь дословно этой строкой: «Привет, мир» {\"type\":\"result\"} \\n 🙂"
        val turn = session.say(payload)
        assertEquals(TurnOutcome.Completed, session.finished(turn))
        val user = session.transcript().first { it.role == MessageRole.User }.text
        assertEquals(payload, user, "the user message survives the stream-json round trip")
        assertTrue(session.transcript().last { it.role == MessageRole.Assistant }.text.contains("Привет"))
        runtime.close()
    }

    @Test
    fun `a second prompt while a turn runs is rejected without disturbing the turn`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val turn = session.say("Write the numbers from 1 to 120, one per line.")
        session.state.first { it is ActiveSessionState.Running }
        val duplicate = runCatching { session.say("Reply PONG.") }
        assertTrue(duplicate.isFailure, "a concurrent prompt must be rejected")
        assertEquals(TurnOutcome.Completed, session.finished(turn))
        runtime.close()
    }

    @Test
    fun `closing the runtime during a turn stops the cli process`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        session.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
        session.state.first { it is ActiveSessionState.Running }
        runtime.close()
        assertNoClaudeProcesses("after the runtime closed")
    }

    @Test
    fun `a reopened session still shows the turns of the first runtime`() = live { fixture ->
        val first = fixture.runtime()
        val created = first.create(CreateSessionRequest(target()))
        created.finished(created.say("Remember the code word MARBLE-9. Reply with OK only."))
        val ref = created.ref
        created.close()
        first.close()
        val reopened = fixture.runtime().attach(ref, ResumeSessionRequest(target()))
        val transcript = reopened.transcript()
        observe("reopened transcript size=${transcript.size}")
        assertTrue(transcript.any { it.role == MessageRole.User && "MARBLE-9" in it.text }, "$transcript")
        assertTrue(transcript.any { it.role == MessageRole.Assistant }, "$transcript")
    }

    @Test
    fun `context usage is reported after a turn`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        session.finished(session.say("Reply with exactly the single word PONG."))
        val usage = session.features.resolve(SessionContextUsage)
        observe("context usage access: $usage")
        assertIs<FeatureAccess.Available<SessionContextUsage>>(usage)
        runtime.close()
    }

    @Test
    fun `a compaction turn completes and the conversation continues`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        session.finished(session.say("Remember the code word QUARTZ-5. Reply with OK only."))
        val compact = session.features.available(SendsPrompts).send(
            PromptRequest(RequestId(UUID.randomUUID().toString()), emptyList(), compaction = ContextCompaction()),
        )
        val outcome = session.finished(compact)
        observe("compaction outcome: $outcome")
        assertEquals(TurnOutcome.Completed, outcome)
        val turn = session.say("What was the code word? Reply with the code word only.")
        assertEquals(TurnOutcome.Completed, session.finished(turn))
        runtime.close()
    }

    @Test
    fun `an effort the model does not advertise is rejected before the turn and an advertised one runs`() =
        live { fixture ->
            val backend = fixture.backend()
            val login = fixture.account.inspect()
            backend.discoverModels(login.source, EngineContext(ClaudeEngine.Id, BINDING))
            val identity = RuntimeIdentity(ClaudeEngine.Id, ClaudeEngine.AuthSource, login.check.revision)
            val runtime = backend.createRuntime(identity)
            val creates = runtime.features.available(CreatesSessions)

            val plain = creates.create(CreateSessionRequest(target("claude-haiku-4-5-20251001")))
            val rejected = assertFailsWith<EngineException> { plain.say("Reply PONG.", effort = "max") }
            observe("unadvertised effort: ${rejected.failure}")
            assertEquals(RequestFailureReason.Invalid, assertIs<EngineFailure.Request>(rejected.failure).reason)

            val capable = creates.create(CreateSessionRequest(target()))
            assertEquals(TurnOutcome.Completed, capable.finished(capable.say("Reply PONG.", effort = "low")))
            runtime.close()
        }

    @Test
    fun `an image and a document attached to a prompt reach the model on the models the CLI lists`() = live { fixture ->
        val image = ResourceRef("picture", "image/png")
        val note = ResourceRef("note", "text/markdown")
        val resolver = ResourceResolver { ref ->
            when (ref) {
                image -> ResolvedResource("picture.png", "image/png", pngWithText("ORCHID-42"))
                note -> ResolvedResource("note.md", "text/markdown", "The vault code is TANGERINE-8.".toByteArray())
                else -> null
            }
        }
        val backend = fixture.backend(resources = resolver)
        val login = fixture.account.inspect()
        val models = backend.discoverModels(login.source, EngineContext(ClaudeEngine.Id, BINDING))
        val unsupported = models.filter { !it.inputSupport.accepts("image/png") }.map { it.target.model.value }
        observe("models without image input: $unsupported")
        assertTrue(
            listOf("default", "opus", "sonnet", "haiku").all { name ->
                models.single { it.target.model.value == name }.inputSupport.accepts("image/png")
            },
            "every recommended alias accepts images: $unsupported",
        )
        val identity = RuntimeIdentity(ClaudeEngine.Id, ClaudeEngine.AuthSource, login.check.revision)
        val runtime = backend.createRuntime(identity)
        val session = runtime.features.available(CreatesSessions).create(CreateSessionRequest(target()))
        val turn = session.features.available(SendsPrompts).send(
            PromptRequest(
                RequestId(UUID.randomUUID().toString()),
                listOf(
                    ContentPart.Text(
                        "Reply with the text drawn in the image, then the vault code of the note. Format: TEXT CODE.",
                    ),
                    ContentPart.Image(image),
                    ContentPart.Resource(note),
                ),
            ),
        )
        assertEquals(TurnOutcome.Completed, session.finished(turn))
        val answer = session.transcript().last { it.role == MessageRole.Assistant }.text
        observe("image answer: $answer")
        assertTrue("ORCHID-42" in answer.uppercase(), "the model read the image: $answer")
        assertTrue("TANGERINE-8" in answer.uppercase(), "the model read the document: $answer")
        runtime.close()
    }

    @Test
    fun `a cli process killed during a turn ends the turn and the session recovers`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val turn = session.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
        session.state.first { it is ActiveSessionState.Running }
        ProcessHandle.current().descendants()
            .filter { it.info().command().orElse("").contains("claude") }
            .forEach { it.destroyForcibly() }
        // The turn's outcome is unknown, so the handle waits for a reconciliation instead of resending anything.
        val unavailable = withTimeout(TURN_TIMEOUT) {
            session.state.first { it is ActiveSessionState.Unavailable || it is ActiveSessionState.Ready }
        }
        observe("after the kill: ${unavailable::class.simpleName}")
        if (unavailable is ActiveSessionState.Unavailable) session.features.available(ReconcilesSession).synchronize()
        val outcome = session.finished(turn)
        observe("killed turn outcome: $outcome")
        assertTrue(outcome != null && outcome != TurnOutcome.Completed, "a killed turn is not a success: $outcome")
        val next = session.say("Reply with exactly the single word READY.")
        assertEquals(TurnOutcome.Completed, session.finished(next))
        assertTrue(session.transcript().last { it.role == MessageRole.Assistant }.text.uppercase().contains("READY"))
        runtime.close()
    }

    @Test
    fun `a cli killed before it opens the session leaves a handle that can be used again`() = live { fixture ->
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(target()))
        val turn = session.say("Write the numbers from 1 to 400, one per line, with a short sentence after each.")
        var killed = false
        var attempts = 0
        while (!killed && attempts++ < KILL_POLLS) {
            ProcessHandle.current().descendants()
                .filter { it.isAlive && it.info().command().orElse("").contains("claude") }
                .forEach { killed = it.destroyForcibly() || killed }
            if (!killed) delay(10.milliseconds)
        }
        assertTrue(killed, "a CLI process was started")
        val settled = withTimeout(TURN_TIMEOUT) {
            session.state.first { it is ActiveSessionState.Unavailable || it is ActiveSessionState.Ready }
        }
        observe("early kill settled: ${settled::class.simpleName} $settled".take(400))
        try {
            session.features.available(ReconcilesSession).synchronize()
            observe("early kill reconciled; state=${session.state.value::class.simpleName}")
        } catch (e: EngineException) {
            log.w(e) { "the reconciliation of a killed session was refused" }
            observe("early kill reconcile refused: ${e.failure}")
        }
        val next = session.say("Reply with exactly the single word READY.")
        assertEquals(TurnOutcome.Completed, session.finished(next))
        observe("early kill: the next prompt on the same handle completed (turn $turn)")
        runtime.close()
    }

    /** A white PNG with [text] in large black letters, drawn headless. */
    private fun pngWithText(text: String): ByteArray {
        val image = BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT)
        graphics.color = Color.BLACK
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, IMAGE_FONT)
        graphics.drawString(text, IMAGE_MARGIN, IMAGE_HEIGHT / 2)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun target(model: String = DEFAULT_MODEL) = EngineTarget(ClaudeEngine.Id, BINDING, ModelId(model))

    private fun live(block: suspend (LiveFixture) -> Unit) = runBlocking {
        withTimeout(SCENARIO_TIMEOUT) { block(LiveFixture(scope, workspaceDirectory)) }
        assertNoClaudeProcesses("after the scenario")
    }

    /** No CLI child of this JVM survives [moment]; the processes get a few seconds to exit. */
    private suspend fun assertNoClaudeProcesses(moment: String) {
        fun running() = ProcessHandle.current().descendants()
            .filter { it.isAlive && it.info().command().orElse("").contains("claude") }
            .toList()
        var leaked = running()
        var attempts = 0
        while (leaked.isNotEmpty() && attempts++ < PROCESS_EXIT_POLLS) {
            delay(PROCESS_EXIT_POLL)
            leaked = running()
        }
        leaked.forEach { it.destroyForcibly() }
        assertTrue(leaked.isEmpty(), "Claude CLI processes leaked $moment: ${leaked.map { it.pid() }}")
    }

    private suspend fun ActiveSession.say(text: String, effort: String? = null): TurnId =
        features.available(SendsPrompts).send(
            PromptRequest(RequestId(UUID.randomUUID().toString()), listOf(ContentPart.Text(text)), effort),
        )

    private suspend fun ActiveSession.finished(turn: TurnId): TurnOutcome? = withTimeout(TURN_TIMEOUT) {
        val ready = state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == turn }
        assertIs<ActiveSessionState.Ready>(ready).lastTurn?.outcome
    }

    /** Answers every permission request of [turn] by allowing or declining it, until the turn is finished. */
    private suspend fun ActiveSession.approveUntilFinished(turn: TurnId, allow: Boolean): TurnOutcome? =
        withTimeout(TURN_TIMEOUT) {
            val gate = features.available(RequestsPermissions)
            var result: TurnOutcome? = null
            var isDone = false
            while (!isDone) {
                val current = state.first {
                    it is ActiveSessionState.AwaitingUserAction ||
                        (it is ActiveSessionState.Ready && it.lastTurn?.id == turn)
                }
                if (current is ActiveSessionState.Ready) {
                    result = current.lastTurn?.outcome
                    isDone = true
                } else {
                    val request = assertIs<ActiveSessionState.AwaitingUserAction>(current).requests.first()
                    observe(
                        "permission: ${request.title} options=${request.options.map { "${it.id.value}:${it.title}" }}",
                    )
                    val option = if (allow) request.options.first() else request.options.last()
                    gate.respond(PermissionDecision(turn, request.id, option.id))
                }
            }
            result
        }

    /** Observations for the run report: the file named by `heartbeat.claude.liveReport`, when set. */
    private fun observe(line: String) {
        System.getProperty("heartbeat.claude.liveReport")?.takeIf(String::isNotBlank)
            ?.let { File(it).appendText("${System.currentTimeMillis()} $line\n") }
    }

    private suspend fun ActiveSession.transcript(): List<Message> = features.available(SessionHistory)
        .page(HistoryPageRequest(limit = 200))
        .items
        .filterIsInstance<SessionItem.Message>()
        .map { message ->
            Message(message.role, message.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text })
        }

    private data class Message(val role: MessageRole, val text: String)

    private class LiveFixture(val scope: CoroutineScope, val workspace: Path) {
        val toggles = TestClaudeToggles()
        val catalog = MemoryClaudeCatalog()
        val transport = transport(executable())
        val account = ClaudeAccount(transport, Clock.System)

        fun backend(
            executable: String? = null,
            resources: ResourceResolver = ResourceResolver { null },
        ): JvmClaudeBackend {
            val process = executable?.let(::transport) ?: transport
            return JvmClaudeBackend(
                process,
                ClaudeAccount(process, Clock.System),
                toggles,
                TestProfileHandle(scope),
                catalog,
                resources = resources,
            )
        }

        suspend fun runtime(
            tools: ProfileAgentTools = NoAgentTools,
            native: ClaudeNativeSupport = MissingClaudeNativeSupport,
        ): ClaudeRuntime {
            val revision = account.inspect().check.revision
            return ClaudeRuntime(
                RuntimeIdentity(ClaudeEngine.Id, ClaudeEngine.AuthSource, revision),
                transport,
                account,
                toggles,
                scope,
                catalog = catalog,
                tools = tools,
                native = native,
            )
        }

        /** Native Read/Write/Bash calls are held for the user's decision, as the `Ask` trust level does. */
        fun askingTools(): ProfileAgentTools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun nativeTools(scope: ToolPolicyScope) =
                ResolvedToolPolicy(nativeOn = setOf("Read", "Write", "Bash"))

            override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall): NativePreparation =
                NativePreparation.Ask(
                    NativeConfirmation {
                        val isAllowed = context.permissions.request(
                            AgentToolApproval(call.name, "Run ${call.name}", call.arguments.toString()),
                        )
                        if (isAllowed) NativeVerdict.Allow else NativeVerdict.Deny("Declined in the live test")
                    },
                )
        }

        fun nativeSupport() = ClaudeNativeSupport {
            ClaudeNativeWorkspace(
                workspace.toString(),
                object : NativeCallClassifier {
                    override fun terminatesHost(command: String) = false
                    override suspend fun isWorkspaceEdit(path: String, workspace: String) =
                        path.startsWith(workspace) && "/.git/" !in path
                },
            )
        }

        private fun transport(executable: String) = ProcessClaudeTransport(
            object : DispatcherProvider {
                override val main: CoroutineDispatcher = Dispatchers.Default
                override val default: CoroutineDispatcher = Dispatchers.Default
                override val io: CoroutineDispatcher = Dispatchers.IO
            },
            ClaudeConfiguration(executable = executable, workspaces = mapOf(WORKSPACE to workspace.toString())),
            object : SearchBridge {
                override fun endpoint() = SearchBridgeEndpoint("http://127.0.0.1:1", "live")
                override fun attach(features: EngineFeatures) = SearchBridgeAttachment { }
            },
            TestLocalWorkspaces(),
        )

        private fun executable(): String = System.getProperty(
            "heartbeat.claude.executable",
        ).orEmpty().ifBlank { "claude" }
    }

    private companion object {
        val BINDING = EngineBindingId("live")
        val WORKSPACE = WorkspaceRef("live-workspace")
        const val DEFAULT_MODEL = "haiku"
        const val IMAGE_WIDTH = 640
        const val IMAGE_HEIGHT = 160
        const val IMAGE_FONT = 72
        const val IMAGE_MARGIN = 20
        val TURN_TIMEOUT = 180.seconds
        val SCENARIO_TIMEOUT = 360.seconds
        val PROCESS_EXIT_POLL = 500.milliseconds
        const val PROCESS_EXIT_POLLS = 20
        const val KILL_POLLS = 400
    }
}
