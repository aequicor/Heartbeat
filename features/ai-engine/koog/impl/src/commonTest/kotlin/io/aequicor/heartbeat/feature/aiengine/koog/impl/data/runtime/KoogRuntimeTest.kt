package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
