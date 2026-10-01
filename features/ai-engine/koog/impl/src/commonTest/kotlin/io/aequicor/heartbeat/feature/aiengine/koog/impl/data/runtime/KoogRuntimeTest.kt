package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogRuntimeTest {
    @Test
    fun searchToolCallContinuesGenerationAndIsRecorded() = runTest {
        val f = KoogTestFixture(this)
        f.searchResults = listOf(SearchResult("https://example.com", "Example", "Snippet"))
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.trySend(StreamFrame.ToolCallComplete("call-1", "web_search", """{"query":"topic"}""", 0))
        f.executor.frames.trySend(StreamFrame.End("tool_calls"))
        f.executor.complete("Answer")
        runCurrent()
        assertEquals(2, f.executor.prompts.size)
        assertEquals(koogSearchTools, f.executor.tools.first())
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        val items = session.features.require(SessionHistory).page().items
        assertEquals(1, items.filterIsInstance<SessionItem.ToolCall>().size)
        assertEquals(1, items.filterIsInstance<SessionItem.ToolResult>().size)
    }

    @Test
    fun unknownToolCallIsReportedToTheModelAsFailure() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.trySend(StreamFrame.ToolCallComplete("call-1", "rm_rf", "{}", 0))
        f.executor.frames.trySend(StreamFrame.End("tool_calls"))
        f.executor.complete("Answer")
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        val result = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolResult>()
        assertIs<EngineFailure>(result.single().failure)
    }

    @Test
    fun searchToolsAreNotSentWhenToggleIsOffOrModelLacksTools() = runTest {
        val f = KoogTestFixture(this)
        f.isSearchEnabled = false
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request("first"))
        f.executor.complete()
        runCurrent()
        f.isSearchEnabled = true
        f.modelSupportsTools = false
        session.features.require(SendsPrompts).send(f.request("second"))
        f.executor.complete()
        runCurrent()
        assertEquals(listOf(emptyList(), emptyList()), f.executor.tools)
    }

    @Test
    fun toolRoundLimitFailsTheTurn() = runTest {
        val f = KoogTestFixture(this)
        f.searchResults = listOf(SearchResult("https://example.com", "Example", "Snippet"))
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        repeat(MAX_TOOL_ROUNDS) { round ->
            f.executor.frames.trySend(StreamFrame.ToolCallComplete("call-$round", "web_search", "{\"query\":\"q\"}", 0))
            f.executor.frames.trySend(StreamFrame.End("tool_calls"))
        }
        runCurrent()
        val outcome = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome
        assertIs<TurnOutcome.Failed>(outcome)
    }

    @Test
    fun acceptedTurnOutlivesLeaseAndPreservesConversation() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val turn = session.features.require(SendsPrompts).send(f.request())
        assertIs<ActiveSessionState.Running>(session.state.value)
        session.close()
        f.executor.complete()
        runCurrent()
        assertEquals(ActiveSessionState.Closed, session.state.value)
        val resumed = f.runtime().attach(session.ref, ResumeSessionRequest(f.target))
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.outcome)
        assertEquals(turn, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.id)
        resumed.features.require(SendsPrompts).send(f.request("next"))
        f.executor.complete("second")
        runCurrent()
        assertEquals(3, f.executor.prompts.last().messages.size)
        val history = resumed.features.require(SessionHistory).page()
        assertEquals(4, history.items.size)
        assertEquals(2, f.executor.closed)
    }

    @Test
    fun secondHandleCannotSubmitWhileNativeSessionIsBusy() = runTest {
        val f = KoogTestFixture(this)
        val first = f.session()
        val second = f.runtime().attach(first.ref, ResumeSessionRequest(f.target))
        first.features.require(SendsPrompts).send(f.request())
        val error = assertFailsWith<EngineException> { second.features.require(SendsPrompts).send(f.request("second")) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
        f.executor.complete()
        runCurrent()
        assertIs<ActiveSessionState.Ready>(second.state.value)
    }

    @Test
    fun staleSourceAndDisabledBindingRejectBeforeOpeningClient() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        f.connections.put(
            KoogConnection(f.binding, f.source.copy(info = f.source.info.copy(revision = AuthRevision.Known("2")))),
        )
        assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(f.request()) }
        assertEquals(0, f.opens)
        f.connections.put(KoogConnection(f.binding.copy(isEnabled = false), f.source))
        assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(f.request()) }
        assertEquals(0, f.opens)
    }

    @Test
    fun unsupportedContentAndForeignRouteAreRejected() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val image = PromptRequest(RequestId("image"), listOf(ContentPart.Image(ResourceRef("image", "image/png"))))
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(image) }
        assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, image.id), error.failure)
        assertFailsWith<EngineException> {
            f.runtime().attach(session.ref, ResumeSessionRequest(f.target, WorkspaceRef("other")))
        }
        assertEquals(0, f.opens)
    }

    @Test
    fun `unadvertised reasoning effort is rejected before opening the provider`() = runTest {
        val fixture = KoogTestFixture(this)
        val session = fixture.session()
        val request = fixture.request().copy(reasoningEffort = "high")
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, request.id), error.failure)
        assertEquals(0, fixture.opens)
    }

    @Test
    fun streamCancellationDoesNotClaimConfirmedRemoteCancellation() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val turn = session.features.require(SendsPrompts).send(f.request())
        session.features.require(CancelsTurns).cancel(turn)
        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        assertEquals(1, f.executor.closed)
    }

    @Test
    fun profileCloseInvalidatesExistingHandles() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.profile.close()
        runCurrent()
        assertEquals(ActiveSessionState.Closed, session.state.value)
        assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(f.request("later")) }
    }

    @Test
    fun callerCancellationDoesNotLoseAcceptedNativeWork() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.records.beforeSave = {
            saving.complete(Unit)
            release.await()
        }
        val caller = async { session.features.require(SendsPrompts).send(f.request()) }
        saving.await()
        caller.cancelAndJoin()
        release.complete(Unit)
        runCurrent()
        f.executor.complete()
        runCurrent()
        assertEquals(TurnOutcome.Completed, f.records.get(session.ref)?.lastTurn?.outcome)
    }

    @Test
    fun closedRuntimeCanBeReplacedAndRestoresHistory() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.complete()
        runCurrent()
        val old = f.runtime()
        old.close()
        val replacement = f.runtime()
        assertNotSame(old, replacement)
        val resumed = replacement.attach(session.ref, ResumeSessionRequest(f.target))
        assertEquals(2, resumed.features.require(SessionHistory).page().items.size)
    }

    @Test
    fun interruptedAcceptanceIsRecoveredFromDurableHistory() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val old = f.runtime()
        val stored = f.adapter.get(session.ref).features.require(SessionHistory)
        val checkpoint = stored.page().checkpoint
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.records.beforeSave = {
            saving.complete(Unit)
            release.await()
        }
        val caller = async {
            assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(f.request()) }
        }
        saving.await()
        val closing = async { old.close() }
        runCurrent()
        release.complete(Unit)
        caller.await()
        closing.await()
        val resumed = f.runtime().attach(session.ref, ResumeSessionRequest(f.target))
        assertEquals(1, resumed.features.require(SessionHistory).page().items.size)
        assertEquals(1, stored.page().items.size)
        assertIs<io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent.HistoryInvalidated>(
            stored.watch(checkpoint).first(),
        )
        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.outcome)
        resumed.features.require(SendsPrompts).send(f.request("next"))
        f.executor.complete()
        runCurrent()
        assertEquals(3, f.records.get(session.ref)?.items?.size)
        assertEquals(2, f.executor.prompts.last().messages.size)
    }

    @Test
    fun `lease released while waiting for the session lock cannot submit`() = runTest {
        val f = KoogTestFixture(this)
        val first = f.session()
        val second = f.runtime().attach(first.ref, ResumeSessionRequest(f.target))
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.records.beforeSave = {
            saving.complete(Unit)
            release.await()
            error("Storage unavailable")
        }
        val holder = async {
            assertFailsWith<EngineException> { first.features.require(SendsPrompts).send(f.request()) }
        }
        saving.await()
        val waiter = async {
            assertFailsWith<EngineException> { second.features.require(SendsPrompts).send(f.request("second")) }
        }
        runCurrent()
        second.close()
        f.records.beforeSave = {}
        release.complete(Unit)
        holder.await()
        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed), waiter.await().failure)
        assertIs<ActiveSessionState.Ready>(first.state.value)
    }

    @Test
    fun `profile closing during acceptance reports an ambiguous outcome instead of a foreign cancellation`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val saving = CompletableDeferred<Unit>()
        f.records.beforeSave = {
            saving.complete(Unit)
            awaitCancellation()
        }
        val request = f.request()
        val caller = async {
            assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        }
        saving.await()
        f.profile.close()
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id), caller.await().failure)
    }

    @Test
    fun `session without leases and running turn is rebuilt from storage on the next attach`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        session.close()
        val kept = f.records.saves
        f.runtime().attach(session.ref, ResumeSessionRequest(f.target)).close()
        assertEquals(kept, f.records.saves, "A running turn keeps its native session")
        f.executor.complete()
        runCurrent()
        val finished = f.records.saves
        val resumed = f.runtime().attach(session.ref, ResumeSessionRequest(f.target))
        assertEquals(finished + 1, f.records.saves, "An idle native session is recovered from storage")
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.outcome)
        assertEquals(2, resumed.features.require(SessionHistory).page().items.size)
    }

    @Test
    fun `closing the last lease while a prompt is being accepted keeps the native session`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.records.beforeSave = {
            saving.complete(Unit)
            release.await()
        }
        val caller = async { session.features.require(SendsPrompts).send(f.request()) }
        saving.await()
        session.close()
        f.records.beforeSave = {}
        release.complete(Unit)
        caller.await()
        val resumed = f.runtime().attach(session.ref, ResumeSessionRequest(f.target))
        assertIs<ActiveSessionState.Running>(resumed.state.value)
        f.executor.complete()
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.outcome)
    }

    @Test
    fun `acceptance that became durable after the runtime closed reports an unknown outcome`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val runtime = f.runtime()
        f.records.beforeSave = { runtime.dispose() }
        val request = f.request()
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id), error.failure)
        assertEquals(request.id, f.records.get(session.ref)?.lastTurn?.request)
    }

    @Test
    fun completedTextBlocksAreCombinedWithoutDuplicatingDeltas() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.send(ai.koog.prompt.streaming.StreamFrame.TextDelta("first", index = 0))
        f.executor.frames.send(ai.koog.prompt.streaming.StreamFrame.TextComplete("first", index = 0))
        f.executor.frames.send(ai.koog.prompt.streaming.StreamFrame.TextDelta("second", index = 1))
        f.executor.frames.send(ai.koog.prompt.streaming.StreamFrame.TextComplete("second", index = 1))
        f.executor.frames.send(ai.koog.prompt.streaming.StreamFrame.End("stop"))
        runCurrent()
        val message = assertIs<io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem.Message>(
            session.features.require(SessionHistory).page().items.last(),
        )
        assertEquals(listOf(ContentPart.Text("firstsecond")), message.parts)
    }
}
