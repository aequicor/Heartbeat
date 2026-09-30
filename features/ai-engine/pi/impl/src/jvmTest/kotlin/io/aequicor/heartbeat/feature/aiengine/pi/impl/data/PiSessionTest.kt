package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiSessionTest {
    @Test
    fun `unknown thinking level is rejected before a native prompt`() = runTest {
        val fixture = fixture()
        val request = prompt("effort").copy(reasoningEffort = "turbo")
        val error = assertFailsWith<EngineException> { fixture.session.send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, request.id), error.failure)
        assertFalse("prompt" in fixture.connection.commands)
        fixture.session.shutdown()
    }

    @Test
    fun `selected thinking level is applied before the prompt and the native level restored later`() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("first").copy(reasoningEffort = "high"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("second").copy(reasoningEffort = "high"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("third"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        val levels = fixture.connection.commands.zip(fixture.connection.fields)
            .filter { it.first == "set_thinking_level" }
            .map { it.second.string("level") }
        assertEquals(listOf("high", "medium"), levels)
        val first = fixture.connection.commands.indexOf("set_thinking_level")
        assertEquals(listOf("get_state", "prompt"), fixture.connection.commands.drop(first + 1).take(2))
        fixture.session.shutdown()
    }

    @Test
    fun `thinking levels follow the model catalog`() {
        fun model(json: String) = Json.parseToJsonElement(json).jsonObject.piThinkingLevels()
        assertEquals(emptyList(), model("""{"reasoning":false}"""))
        assertEquals(listOf("off", "minimal", "low", "medium", "high"), model("""{"reasoning":true}"""))
        assertEquals(
            listOf("off", "low", "medium", "high", "xhigh"),
            model("""{"reasoning":true,"thinkingLevelMap":{"minimal":null,"xhigh":"max"}}"""),
        )
        assertEquals(
            listOf("off", PI_THINKING_ON),
            model("""{"reasoning":true,"compat":{"thinkingFormat":"qwen","supportsReasoningEffort":false}}"""),
        )
        assertEquals(
            listOf("low", "medium", "xhigh"),
            model(
                """{"reasoning":true,"compat":{"thinkingFormat":"qwen","supportsReasoningEffort":true},
                "thinkingLevelMap":{"off":null,"minimal":null,"high":null,"xhigh":"xhigh"}}""",
            ),
        )
        assertEquals("medium", piThinkingLevel(PI_THINKING_ON))
        assertEquals("low", piThinkingLevel("low"))
    }

    @Test
    fun `caller cancellation keeps the native turn and close releases the process after it settles`() = runTest {
        val fixture = fixture()
        val send = async { fixture.session.send(prompt("first")) }
        runCurrent()
        send.cancel()
        fixture.connection.event(record("""{"type":"agent_start"}"""))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.close()
        assertEquals(ActiveSessionState.Closed, fixture.session.state.value)
        assertFalse(fixture.connection.closed)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        assertTrue(fixture.connection.closed)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `closing an idle session releases its process`() = runTest {
        val fixture = fixture()
        fixture.session.close()
        assertTrue(fixture.connection.closed)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `synchronize after process loss restarts pi on the same transcript`() = runTest {
        val fixture = fixture()
        val ref = fixture.session.ref
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val turn = fixture.session.send(prompt("first"))
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
        fixture.session.synchronize()
        val restarted = fixture.connections.last()
        assertEquals(2, fixture.connections.size)
        assertEquals(listOf("switch_session", "get_state", "get_state"), restarted.commands)
        assertEquals("native.jsonl", restarted.fields.first().string("sessionPath"))
        assertEquals(ref, fixture.session.ref)
        assertFalse(restarted.closed)
        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertEquals(turn, ready.lastTurn?.id)
        assertEquals(TurnOutcome.Unknown, ready.lastTurn?.outcome)
        restarted.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("continued"))
        assertEquals("prompt", restarted.commands.last())
        fixture.session.shutdown()
    }

    @Test
    fun `resumed session starts pi on the stored transcript`() = runTest {
        val ref = SessionRef(PiEngineId, PiSessionSource, "native")
        val fixture = fixture(transcript = PiTranscript(ref, "stored.jsonl"))
        assertEquals(listOf("switch_session", "get_entries", "set_model", "get_state"), fixture.connection.commands)
        assertEquals("stored.jsonl", fixture.connection.fields.first().string("sessionPath"))
        assertEquals(ref, fixture.session.ref)
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            fixture.session.features.resolve(SessionHistory),
        ).feature.page(HistoryPageRequest())
        // The whole active branch: compacted messages stay, the abandoned branch and the summary do not.
        assertEquals(
            listOf("Stored", "Reply", "Later"),
            history.items.map { item ->
                assertIs<SessionItem.Message>(item).parts.joinToString { (it as ContentPart.Text).text }
            },
        )
        // Restored items carry new ids; complete coverage lets observers replace their copy instead of adding it.
        assertEquals(HistoryCoverage.Complete, history.coverage)
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `resuming a branch with a missing parent exposes no regenerated partial replay`() = runTest {
        val ref = SessionRef(PiEngineId, PiSessionSource, "native")
        val fixture = fixture(transcript = PiTranscript(ref, "stored.jsonl")) { _, connection ->
            connection.entries = """{"leafId":"tail","entries":[
                {"type":"message","id":"tail","parentId":"missing",
                    "message":{"role":"assistant","content":"Already saved"}}]}"""
        }
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            fixture.session.features.resolve(SessionHistory),
        ).feature.page()

        assertEquals(emptyList(), history.items)
        assertEquals(HistoryCoverage.Partial, history.coverage)
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `resume never adopts a different native session or an unreadable transcript`() = runTest {
        val opened = mutableListOf<FakeConnection>()
        fun stored(nativeId: String) = PiTranscript(SessionRef(PiEngineId, PiSessionSource, nativeId), "stored.jsonl")
        val changed = assertFailsWith<EngineException> {
            fixture(transcript = stored("stored")) { _, connection -> opened += connection }
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), changed.failure)
        val unreadable = assertFailsWith<EngineException> {
            fixture(transcript = stored("native")) { _, connection ->
                connection.entries = "{}"
                opened += connection
            }
        }
        assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), unreadable.failure)
        assertEquals(listOf(true, true), opened.map { it.closed })
    }

    @Test
    fun `rejected model change keeps the session ready`() = runTest {
        val fixture = fixture()
        val failure = assertFailsWith<EngineException> { fixture.session.switchTo(ModelId("anthropic/missing")) }
        assertIs<EngineFailure.Request>(failure.failure)
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `validation failure before delivery is reported as a definite send failure`() = runTest {
        var checks = 0
        val fixture = fixture(validate = {
            checks++
            if (checks > 1) throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        })
        val failure = assertFailsWith<EngineException> { fixture.session.send(prompt("blocked")) }
        assertIs<EngineFailure.Access>(failure.failure)
        assertFalse("prompt" in fixture.connection.commands)
        fixture.session.shutdown()
    }

    @Test
    fun overlappingSendIsRejectedAndAgentEndDoesNotPrematurelyFinishRetry() = runTest {
        val fixture = fixture()
        val send = async { fixture.session.send(prompt("first")) }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_start"}"""))
        assertFailsWith<EngineException> { fixture.session.send(prompt("second")) }
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        runCurrent()
        send.await()
        fixture.connection.event(record("""{"type":"agent_end","willRetry":true}"""))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.connection.event(
            record("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error"}}"""),
        )
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertIs<TurnOutcome.Failed>(ready.lastTurn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun quickCompletionBeforeAcceptanceResponseKeepsCompletedOutcome() = runTest {
        val fixture = fixture()
        val send = async { fixture.session.send(prompt("first")) }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        runCurrent()
        assertEquals(send.await(), assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn?.id)
        assertEquals(
            TurnOutcome.Completed,
            assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn?.outcome,
        )
        fixture.session.shutdown()
    }

    @Test
    fun shutdownBeforeNativeAcceptanceReportsUnknownDelivery() = runTest {
        val fixture = fixture()
        val sending = async { assertFailsWith<EngineException> { fixture.session.send(prompt("unsent")) } }
        runCurrent()
        fixture.session.shutdown()
        val failure = assertIs<EngineFailure.Request>(sending.await().failure)
        assertEquals(RequestFailureReason.OutcomeUnknown, failure.reason)
        assertEquals(ActiveSessionState.Closed, fixture.session.state.value)
    }

    @Test
    fun finishingBeforeAbortResponseDoesNotPermitAbortToReachANewTurn() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val first = fixture.session.send(prompt("first"))
        val cancelling = async { fixture.session.cancel(first) }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertFailsWith<EngineException> { fixture.session.send(prompt("too-early")) }
        fixture.connection.abortAck.complete(JsonObject(emptyMap()))
        cancelling.await()
        assertEquals(1, fixture.connection.commands.count { it == "abort" })
        fixture.session.send(prompt("next"))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun cancellingModelChangeCallerStillRecordsTheNativeModel() = runTest {
        val fixture = fixture()
        val changing = launch { fixture.session.switchTo(ModelId("anthropic/other")) }
        runCurrent()
        changing.cancel()
        fixture.connection.modelAck.complete(JsonObject(emptyMap()))
        changing.join()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("new-model"))
        val running = assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        assertEquals(ModelId("anthropic/other"), running.turn.target.model)
        fixture.session.shutdown()
    }

    @Test
    fun `host termination waits for explicit allowance at every trust level`() = runTest {
        TrustLevel.entries.forEach { level ->
            val fixture = fixture()
            val turn = fixture.runningTurn(level)
            fixture.connection.event(approval("ui-1", target = "./gradlew --stop"))
            assertTrue(fixture.connection.sent.isEmpty())
            val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
            assertEquals("bash: ./gradlew --stop", awaiting.requests.single().title)
            assertEquals("Завершить Heartbeat и выполнить", awaiting.requests.single().options.first().title)
            val permissions = assertIs<FeatureAccess.Available<RequestsPermissions>>(
                fixture.session.features.resolve(RequestsPermissions),
            ).feature
            permissions.respond(PermissionDecision(turn, PermissionRequestId("ui-1"), PiApprovalAllow))
            runCurrent()
            assertEquals(listOf(answer("ui-1", "confirmed", true)), fixture.connection.sent)
            assertIs<ActiveSessionState.Running>(fixture.session.state.value)
            fixture.session.shutdown()
        }
    }

    @Test
    fun `full trust answers ordinary tool approvals without the user`() = runTest {
        val fixture = fixture()
        fixture.runningTurn(TrustLevel.Full)
        fixture.connection.event(approval("ui-t1"))
        fixture.connection.event(approval("ui-t2", tool = "write"))
        assertEquals(
            listOf(answer("ui-t1", "confirmed", true), answer("ui-t2", "confirmed", true)),
            fixture.connection.sent,
        )
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `edit trust allows file edits and still asks before commands`() = runTest {
        val fixture = fixture()
        fixture.runningTurn(TrustLevel.AutoEdits)
        val notes = TestWorkspace.resolve("notes.md").toString()
        fixture.connection.event(approval("ui-e1", target = notes, tool = "edit", path = notes))
        fixture.connection.event(approval("ui-e2"))
        assertEquals(listOf(answer("ui-e1", "confirmed", true)), fixture.connection.sent)
        val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals("bash: ls -la", awaiting.requests.single().title)
        fixture.session.shutdown()
    }

    @Test
    fun `edit trust asks before writes outside the workspace or into git metadata`() = runTest {
        val fixture = fixture()
        fixture.runningTurn(TrustLevel.AutoEdits)
        val outside = TestWorkspace.resolveSibling("pi-outside").resolve("profile").toString()
        val hook = TestWorkspace.resolve(".git/hooks/pre-commit").toString()
        fixture.connection.event(approval("ui-o1", target = outside, tool = "write", path = outside))
        fixture.connection.event(approval("ui-o2", target = hook, tool = "write", path = hook))
        // Paths Pi rewrites are not pinned by the extension and always reach the user.
        fixture.connection.event(approval("ui-o3", target = "~/.zshrc", tool = "edit"))
        fixture.connection.event(approval("ui-o4", target = "notes.md", tool = "write"))
        assertTrue(fixture.connection.sent.isEmpty())
        val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals(4, awaiting.requests.size)
        fixture.session.shutdown()
    }

    @Test
    fun `nothing is trusted while the turn is being interrupted`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn(TrustLevel.Full)
        val cancel = async { fixture.session.cancel(turn) }
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(fixture.session.state.value)
        fixture.connection.event(approval("ui-i1", tool = "write"))
        assertTrue(fixture.connection.sent.none { it == answer("ui-i1", "confirmed", true) })
        fixture.connection.abortAck.complete(JsonObject(emptyMap()))
        cancel.await()
        fixture.session.shutdown()
    }

    @Test
    fun `an explicit ask level leaves file edits to the user`() = runTest {
        val fixture = fixture()
        fixture.runningTurn(TrustLevel.Ask)
        fixture.connection.event(approval("ui-a1", target = "notes.md", tool = "edit"))
        assertTrue(fixture.connection.sent.isEmpty())
        assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `only pinned absolute paths inside the workspace are edits`() {
        val workspace = TestWorkspace
        assertTrue(isWorkspaceEdit(workspace.resolve("src/Main.kt").toString(), workspace))
        assertTrue(isWorkspaceEdit(workspace.resolve("new.txt").toString(), workspace))
        assertFalse(isWorkspaceEdit(workspace.resolve("../sibling.txt").toString(), workspace))
        assertFalse(isWorkspaceEdit("src/Main.kt", workspace))
        assertFalse(isWorkspaceEdit("@${workspace.resolve("new.txt")}", workspace))
        assertFalse(isWorkspaceEdit("file://${workspace.resolve("new.txt")}", workspace))
        assertFalse(isWorkspaceEdit("FILE:///etc/hosts", workspace))
        assertFalse(isWorkspaceEdit("@~/.zshrc", workspace))
        assertFalse(isWorkspaceEdit("", workspace))
    }

    @Test
    fun `workspace edits resolve links so a link cannot lead outside`() {
        // Creating links on Windows needs a privilege developers usually lack.
        if (System.getProperty("os.name").startsWith("Windows")) return
        val escape = TestWorkspace.resolve("escape")
        if (!Files.isSymbolicLink(escape)) {
            Files.createSymbolicLink(escape, TestWorkspace.parent).toFile().deleteOnExit()
        }
        assertFalse(isWorkspaceEdit(escape.resolve("file.txt").toString(), TestWorkspace))
    }

    @Test
    fun `full trust respects denied host termination`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn(TrustLevel.Full)
        fixture.connection.event(approval("ui-2", target = "taskkill /F /IM java.exe"))
        fixture.session.respond(PermissionDecision(turn, PermissionRequestId("ui-2"), PermissionOptionId("deny")))
        runCurrent()
        assertEquals(listOf(answer("ui-2", "confirmed", false)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `dialogs nobody can answer are dismissed so pi blocks the tool`() = runTest {
        val fixture = fixture()
        fixture.connection.event(approval("idle"))
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"other","method":"input","title":"Name?"}"""),
        )
        fixture.runningTurn()
        fixture.connection.event(record("""{"type":"extension_ui_request","id":"n","method":"notify"}"""))
        assertEquals(
            listOf(answer("idle", "cancelled", true), answer("other", "cancelled", true)),
            fixture.connection.sent,
        )
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `select dialog becomes a single choice and the chosen value reaches pi`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record(
                """{"type":"extension_ui_request","id":"s","method":"select","title":"Pick",""" +
                    """"options":["red","blue"]}""",
            ),
        )
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests.single()
        assertEquals(
            PermissionInput.SingleChoice(listOf(PermissionChoice("0", "red"), PermissionChoice("1", "blue"))),
            request.input,
        )
        fixture.session.respond(
            PermissionDecision(turn, request.id, PermissionOptionId("answer"), PermissionAnswer.Selected(listOf("1"))),
        )
        runCurrent()
        assertEquals(listOf(valueAnswer("s", "blue")), fixture.connection.sent)
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `text dialogs send the typed value and skipping cancels them`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"t","method":"editor","title":"Notes"}"""),
        )
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"u","method":"input","title":"Name?"}"""),
        )
        val requests = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests
        assertEquals(PermissionInput.FreeText(isMultiline = true), requests.first().input)
        fixture.session.respond(
            PermissionDecision(
                turn,
                PermissionRequestId("t"),
                PermissionOptionId("answer"),
                PermissionAnswer.Text("hi"),
            ),
        )
        runCurrent()
        fixture.session.respond(PermissionDecision(turn, PermissionRequestId("u"), PermissionOptionId("skip")))
        runCurrent()
        assertEquals(listOf(valueAnswer("t", "hi"), answer("u", "cancelled", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `an undelivered dialog answer keeps the request pending in the session`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"t","method":"input","title":"Name?"}"""),
        )
        val decision = PermissionDecision(
            turn,
            PermissionRequestId("t"),
            PermissionOptionId("answer"),
            PermissionAnswer.Text("hi"),
        )
        fixture.connection.sendFailure =
            EngineException(EngineFailure.Transport(TransportFailureReason.ServiceUnavailable))
        fixture.session.respond(decision)
        runCurrent()
        assertTrue(fixture.connection.sent.isEmpty())
        // Pi still waits for the dialog, so closing the session must still decline it.
        fixture.session.close()
        assertEquals(listOf(answer("t", "cancelled", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `plain confirm dialog is answered with the chosen option`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"c","method":"confirm","title":"Go?","message":"Sure"}"""),
        )
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests.single()
        assertEquals("Sure", request.description)
        fixture.session.respond(PermissionDecision(turn, request.id, PermissionOptionId("yes")))
        runCurrent()
        assertEquals(listOf(answer("c", "confirmed", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `cancelling a turn dismisses its pending approvals before aborting`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(approval("ui-3"))
        fixture.connection.abortAck.complete(JsonObject(emptyMap()))
        fixture.session.cancel(turn)
        assertEquals(listOf(answer("ui-3", "cancelled", true)), fixture.connection.sent)
        assertEquals("abort", fixture.connection.commands.last())
        fixture.session.shutdown()
    }

    @Test
    fun `close after a rejected prompt releases the process`() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.completeExceptionally(
            EngineException(EngineFailure.Request(RequestFailureReason.Invalid)),
        )
        assertFailsWith<EngineException> { fixture.session.send(prompt("rejected")) }
        fixture.session.close()
        assertTrue(fixture.connection.closed)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `failed transcript reattachment never keeps the restarted process`() = runTest {
        val fixture = fixture { index, connection ->
            if (index == 1) {
                connection.switchFailure = EngineException(
                    EngineFailure.Request(RequestFailureReason.Invalid),
                )
            }
        }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        assertFailsWith<EngineException> { fixture.session.synchronize() }
        assertTrue(fixture.connections[1].closed)
        fixture.session.synchronize()
        assertEquals(3, fixture.connections.size)
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `process loss before the first persisted assistant message never adopts a new session`() = runTest {
        val fixture = fixture { index, connection ->
            // Pi returns a path immediately, but writes the transcript only after the first assistant message.
            // Switching to the missing file succeeds and silently creates a new native session.
            if (index > 0) connection.sessionIdAfterSwitch = "replacement-$index"
        }
        val ref = fixture.session.ref
        val turn = fixture.runningTurn()
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        repeat(2) { attempt ->
            val failure = assertFailsWith<EngineException> { fixture.session.synchronize() }
            assertEquals(EngineFailure.Session(SessionFailureReason.Changed), failure.failure)
            val rejected = fixture.connections[attempt + 1]
            assertEquals(listOf("switch_session", "get_state"), rejected.commands)
            assertEquals("native.jsonl", rejected.fields.first().string("sessionPath"))
            assertTrue(rejected.closed)
            assertEquals(ref, fixture.session.ref)
            val unavailable = assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
            assertEquals(turn, unavailable.activeTurn?.id)
            assertFailsWith<EngineException> { fixture.session.send(prompt("retry-$attempt")) }
            assertFalse("prompt" in rejected.commands)
            // A rejected process must not settle the remembered turn through late callbacks.
            rejected.event(record("""{"type":"agent_settled"}"""))
            assertEquals(unavailable, fixture.session.state.value)
        }
        assertEquals(3, fixture.connections.size)
        fixture.session.shutdown()
    }

    @Test
    fun `callbacks of a replaced process are ignored`() = runTest {
        val fixture = fixture()
        val stale = fixture.connection
        stale.isOpen = false
        stale.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.session.synchronize()
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        stale.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        stale.event(record("""{"type":"agent_start"}"""))
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `closing while an approval is pending declines it and releases after the turn settles`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        fixture.connection.event(approval("ui-4"))
        fixture.session.close()
        assertEquals(listOf(answer("ui-4", "cancelled", true)), fixture.connection.sent)
        assertFalse(fixture.connection.closed)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun `approval text shows hidden characters and oversized commands are blocked`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        fixture.connection.event(approval("ui-5", "ls\n‮rm -rf"))
        val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals("bash: ls\\n\\u202erm -rf", awaiting.requests.single().title)
        fixture.connection.event(approval("ui-6", "x".repeat(4_001)))
        assertEquals(listOf(answer("ui-6", "cancelled", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }
}
