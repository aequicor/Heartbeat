package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IsolatedServiceLifecycleTest {
    @Test
    fun `failed submission reconciles an idle session and releases the service slot`() = runTest {
        val f = IsolatedTurnFixture().apply { failsOnSend = true }
        val prompts = prompts(f, backgroundScope)
        val prompt = prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds))
        assertFalse(prompt.isAnswered)
        assertFalse(prompt.toString().contains("private source"))
        assertEquals(1, f.reconciles)
        assertEquals(0, f.cancels)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.failsOnSend = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `reconciliation revealing an active prompt cancels it before releasing the slot`() = runTest {
        val f = IsolatedTurnFixture().apply {
            failsOnSend = true
            remainsActiveAfterReconcile = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.reconciles)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.failsOnSend = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
    }

    @Test
    fun `failed reconciliation retains an unresolved prompt and its restrictions`() = runTest {
        val f = IsolatedTurnFixture().apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.reconciles)
        assertEquals(0, f.cancels)
        assertFalse(f.closed)
        assertFalse(f.archived)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `terminal unavailable session releases the slot without unnecessary recovery`() = runTest {
        val f = IsolatedTurnFixture().apply { finishesUnavailable = true }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertEquals(0, f.reconciles)
        assertEquals(0, f.cancels)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.finishesUnavailable = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
    }

    @Test
    fun `late unavailable outcome releases the retained prompt only for its own turn`() = runTest {
        val f = IsolatedTurnFixture().apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        f.finish(TurnId("unrelated"), isUnavailable = true)
        runCurrent()
        assertFalse(f.closed)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        f.finish(isUnavailable = true)
        runCurrent()
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.failsOnSend = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "third", timeout = 5.seconds)).isAnswered)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `detached handle does not prove an unresolved prompt has stopped`() = runTest {
        val f = IsolatedTurnFixture().apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        f.detach()
        runCurrent()
        assertFalse(f.archived)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `prompt timeout cancels closes and returns uncertainty`() = runTest {
        val f = IsolatedTurnFixture().apply { outcome = null }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `unknown cancellation holds the only service slot and restrictions within the grace period`() = runTest {
        val f = IsolatedTurnFixture().apply {
            outcome = null
            ignoresCancel = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertFalse(f.closed)
        val refused = prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds))
        assertFalse(refused.isAnswered)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `a running prompt ignoring cancellation is closed and abandoned after the grace period`() = runTest {
        val f = IsolatedTurnFixture().apply {
            outcome = null
            ignoresCancel = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        advanceTimeBy(GRACE + 1.seconds)
        assertTrue(f.closed)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `late terminal acknowledgement closes the retained session and allows the next prompt`() = runTest {
        val f = IsolatedTurnFixture().apply {
            outcome = null
            ignoresCancel = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        f.finish(TurnId("unrelated"))
        runCurrent()
        assertFalse(f.closed)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.creations.size)
        f.finish()
        runCurrent()
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.outcome = TurnOutcome.Completed
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "third", timeout = 5.seconds)).isAnswered)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `profile shutdown closes the retained service handle`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val f = IsolatedTurnFixture().apply {
            outcome = null
            ignoresCancel = true
        }
        val prompts = prompts(f, owner)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertFalse(f.closed)
        owner.cancel()
        runCurrent()
        assertTrue(f.closed)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.creations.size)
    }

    @Test
    fun `unresponsive close is bounded and releases the slot as abandoned`() = runTest {
        val f = IsolatedTurnFixture().apply { hangsOnClose = true }
        val prompts = prompts(f, backgroundScope)
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertFalse(f.closed)
        advanceTimeBy(30.seconds)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        f.hangsOnClose = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `a handle whose close failed is closed again when the profile closes`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val f = IsolatedTurnFixture().apply { hangsOnClose = true }
        val prompts = prompts(f, owner)
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        advanceTimeBy(30.seconds)
        assertFalse(f.closed)
        f.hangsOnClose = false
        owner.cancel()
        runCurrent()
        assertTrue(f.closed)
    }

    @Test
    fun `a prompt leaving the queue after the profile started closing opens no session`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val gate = CompletableDeferred<Unit>()
        val f = IsolatedTurnFixture().apply { createGate = gate }
        val prompts = prompts(f, owner)
        val first = async { prompts.ask(IsolatedPrompt(f.target, "first", timeout = 5.seconds)) }
        val second = async { prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)) }
        runCurrent()
        owner.cancel()
        gate.complete(Unit)
        first.await()
        val refused = second.await()
        assertFalse(refused.isAnswered)
        assertEquals(1, f.creations.size)
    }

    @Test
    fun `unresolved prompt releases its slot after the grace period and keeps its restrictions`() = runTest {
        val f = IsolatedTurnFixture().apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        advanceTimeBy(GRACE + 11.seconds)
        assertTrue(f.closed)
        assertFalse(f.archived)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        f.failsOnSend = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "second", timeout = 5.seconds)).isAnswered)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `a permission request is never answered or interpreted as a safe verdict`() = runTest {
        val f = IsolatedTurnFixture().apply { asks = true }
        val prompts = prompts(f, backgroundScope)
        assertFalse(prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)).isAnswered)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `cancelling the parent cleans up and propagates`() = runTest {
        val f = IsolatedTurnFixture().apply { outcome = null }
        val prompts = prompts(f, backgroundScope)
        val task = async { prompts.ask(IsolatedPrompt(f.target, "{}", timeout = 5.seconds)) }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(f.closed)
        assertEquals(1, f.cancels)
    }

    private fun prompts(f: IsolatedTurnFixture, owner: CoroutineScope) = FacadeIsolatedPrompts(
        lazyOf(f.facade),
        f.policy,
        owner,
        IsolatedTurnSlots(capacity = 1, queueWait = 10.seconds),
        GRACE,
    )
}

private val GRACE = 1.minutes
private val IsolatedAnswer.isAnswered: Boolean get() = this is IsolatedAnswer.Answered
