package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.browser.api.BrowserEffect
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserEffectsTest {
    @Test
    fun `a disabled browser never observes network recovery and an enabled one forwards it`() = runTest {
        val enabled = MutableStateFlow(false)
        val restored = MutableSharedFlow<Unit>()
        val session = TestBrowserSession(networkRestored = restored)
        val sent = mutableListOf<BrowserIntent>()
        val feedback = object : EffectScope<BrowserIntent> {
            override suspend fun send(intent: BrowserIntent): SendResult {
                sent += intent
                return SendResult.Accepted
            }
        }
        val observation = backgroundScope.launch {
            BrowserEffects(BrowserAvailability { enabled }, session).handle(BrowserEffect.Observe, feedback)
        }
        runCurrent()
        assertEquals(0, restored.subscriptionCount.value)
        enabled.value = true
        runCurrent()
        restored.emit(Unit)
        runCurrent()
        assertTrue(BrowserIntent.Internal.NetworkRestored in sent)
        enabled.value = false
        runCurrent()
        assertEquals(0, restored.subscriptionCount.value)
        observation.cancel()
    }

    @Test
    fun `live toggle revokes the session before notifying the machine and observation survives`() = runTest {
        val enabled = MutableStateFlow(false)
        val session = TestBrowserSession()
        val sent = mutableListOf<BrowserIntent>()
        val feedback = object : EffectScope<BrowserIntent> {
            override suspend fun send(intent: BrowserIntent): SendResult {
                if (intent is BrowserIntent.Internal.AvailabilityChanged) {
                    assertEquals(intent.isEnabled, session.availability.last())
                }
                sent += intent
                return SendResult.Accepted
            }
        }
        val effects = BrowserEffects(BrowserAvailability { enabled }, session)
        val observation = backgroundScope.launch { effects.handle(BrowserEffect.Observe, feedback) }
        runCurrent()
        enabled.value = true
        runCurrent()
        session.pages.value = BrowserPage("https://example.com", isLoading = true)
        runCurrent()
        enabled.value = false
        runCurrent()
        enabled.value = true
        runCurrent()
        assertEquals(listOf(false, true, false, true), session.availability)
        assertTrue(BrowserIntent.Internal.PageChanged(session.pages.value) in sent)
        observation.cancel()
        runCurrent()
        assertEquals(0, enabled.subscriptionCount.value)
        assertEquals(0, session.pages.subscriptionCount.value)
    }

    @Test
    fun `restored selected page is delivered after the machine becomes enabled`() = runTest {
        val restored = BrowserPage("https://example.com/restored", title = "Restored")
        val session = TestBrowserSession(onEnable = { restored })
        val feedback = RecordingFeedback()
        val effects = BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
        backgroundScope.launch { effects.handle(BrowserEffect.Observe, feedback) }
        runCurrent()
        val available = feedback.sent.indexOf(BrowserIntent.Internal.AvailabilityChanged(true))
        assertTrue(available >= 0)
        assertTrue(feedback.sent.drop(available + 1).contains(BrowserIntent.Internal.PageChanged(restored)))
    }

    @Test
    fun `navigation effects use domain commands without accessing presentation`() = runTest {
        val session = TestBrowserSession()
        val effects = BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
        val feedback = object : EffectScope<BrowserIntent> {
            override suspend fun send(intent: BrowserIntent) = SendResult.Accepted
        }
        listOf(
            BrowserEffect.Load("https://example.com"),
            BrowserEffect.Back,
            BrowserEffect.Forward,
            BrowserEffect.Reload,
            BrowserEffect.Stop,
        ).forEach { effects.handle(it, feedback) }
        assertEquals(
            listOf(
                BrowserCommand.Load("https://example.com"),
                BrowserCommand.Back,
                BrowserCommand.Forward,
                BrowserCommand.Reload,
                BrowserCommand.Stop,
            ),
            session.commands,
        )
    }

    @Test
    fun `operation failure becomes its single result`() = runTest {
        val session = TestBrowserSession(perform = { error("https://private.example/secret") })
        val feedback = RecordingFeedback()
        BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
            .handle(BrowserEffect.Perform(request), feedback)
        assertEquals<List<BrowserIntent>>(
            listOf(BrowserIntent.Internal.Completed(BrowserResult(request.id, error = "Browser operation failed"))),
            feedback.sent,
        )
    }

    @Test
    fun `cancellation raised inside an operation is left to the runtime to answer once`() = runTest {
        val session = TestBrowserSession(
            perform = { throw CancellationException("revoked") },
            cancel = { throw CancellationException("timeout") },
        )
        val feedback = RecordingFeedback()
        val effects = BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
        assertFailsWith<CancellationException> { effects.handle(BrowserEffect.Perform(request), feedback) }
        assertFailsWith<CancellationException> { effects.handle(BrowserEffect.CancelOperation(request.id), feedback) }
        assertTrue(feedback.sent.isEmpty())
    }

    @Test
    fun `stopping the machine cancels an operation without feedback`() = runTest {
        val session = TestBrowserSession(perform = { awaitCancellation() })
        val feedback = RecordingFeedback()
        val effects = BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
        val job = launch { effects.handle(BrowserEffect.Perform(request), feedback) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(feedback.sent.isEmpty())
    }

    @Test
    fun `failed cancellation is still acknowledged and failed selection is only logged`() = runTest {
        val session = TestBrowserSession(
            cancel = { error("native failure") },
            select = { error("stale tab") },
        )
        val feedback = RecordingFeedback()
        val effects = BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
        effects.handle(BrowserEffect.CancelOperation(request.id), feedback)
        effects.handle(BrowserEffect.SelectTab("tab"), feedback)
        assertEquals<List<BrowserIntent>>(listOf(BrowserIntent.Internal.OperationCancelled(request.id)), feedback.sent)
    }

    @Test
    fun `observer failure revokes the session before reporting the failure`() = runTest {
        val session = TestBrowserSession()
        val availability = flow {
            emit(true)
            error("toggle storage failed")
        }
        val feedback = RecordingFeedback()
        val effects = BrowserEffects(BrowserAvailability { availability }, session)
        assertFailsWith<IllegalStateException> { effects.handle(BrowserEffect.Observe, feedback) }
        assertEquals(listOf(true, false), session.availability)
        val cancelledInside = BrowserEffects(
            BrowserAvailability { flow { throw CancellationException("upstream") } },
            session,
        )
        assertFailsWith<CancellationException> { cancelledInside.handle(BrowserEffect.Observe, feedback) }
        assertEquals(listOf(true, false, false), session.availability)
    }

    @Test
    fun `observation restarts only after a bounded backoff`() = runTest {
        assertEquals(1.seconds, browserObserverRetryDelay(1))
        assertEquals(4.seconds, browserObserverRetryDelay(3))
        assertEquals(60.seconds, browserObserverRetryDelay(Int.MAX_VALUE))
        val enabled = MutableStateFlow(true)
        val session = TestBrowserSession()
        val effects = BrowserEffects(BrowserAvailability { enabled }, session)
        backgroundScope.launch { effects.handle(BrowserEffect.Reobserve(2), RecordingFeedback()) }
        advanceTimeBy(2.seconds - 1.seconds / 1000)
        runCurrent()
        assertEquals(0, enabled.subscriptionCount.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(true), session.availability)
    }
}

private val request = BrowserRequest("operation", BrowserOperation.NewTab)

private class RecordingFeedback : EffectScope<BrowserIntent> {
    val sent = mutableListOf<BrowserIntent>()

    override suspend fun send(intent: BrowserIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private class TestBrowserSession(
    private val perform: suspend () -> BrowserResult = { error("unused") },
    private val cancel: suspend () -> Unit = {},
    private val select: suspend () -> Unit = {},
    private val onEnable: () -> BrowserPage = { BrowserPage() },
    override val networkRestored: Flow<Unit> = emptyFlow(),
) : BrowserSession {
    override val pages = MutableStateFlow(BrowserPage())
    val availability = mutableListOf<Boolean>()
    val commands = mutableListOf<BrowserCommand>()

    override suspend fun perform(request: BrowserRequest): BrowserResult = perform()

    override suspend fun cancelOperation(id: String) = cancel()

    override suspend fun selectTab(id: String) = select()

    override suspend fun setEnabled(isEnabled: Boolean) {
        availability += isEnabled
        if (isEnabled) pages.value = onEnable()
    }

    override suspend fun execute(command: BrowserCommand) {
        commands += command
    }
}
