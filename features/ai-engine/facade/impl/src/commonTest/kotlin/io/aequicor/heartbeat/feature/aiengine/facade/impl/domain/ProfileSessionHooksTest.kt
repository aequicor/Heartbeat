package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.HookSessions
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ProfileSessionHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSessionHooksTest {
    @Test
    fun `ownership is synchronous and another handle cannot inherit interception`() = runTest {
        val hook = RecordingHook()
        val dispatcher = dispatcher(hook)
        dispatcher.bindTurn(CONTEXT)
        assertNull(dispatcher.context(SESSION, REQUEST, TURN))
        dispatcher.observe(SessionLifecycle.Opened(CONTEXT))
        dispatcher.bindTurn(CONTEXT)
        assertEquals(CONTEXT, dispatcher.context(SESSION, REQUEST, TURN))
        assertEquals(ToolHookVerdict.Continue, dispatcher.beforeTool(call(CONTEXT.copy(owner = SessionOwner("other")))))
        assertEquals(0, hook.calls)
        dispatcher.releaseTurn(SESSION, TURN)
        assertNull(dispatcher.context(SESSION, REQUEST, TURN))
    }

    @Test
    fun `observations belong to accepted turns and are deduplicated`() = runTest {
        val hook = RecordingHook()
        val dispatcher = dispatcher(hook)
        dispatcher.observe(SessionLifecycle.Opened(CONTEXT))
        dispatcher.observe(SessionLifecycle.Opened(CONTEXT))
        dispatcher.bindTurn(CONTEXT)
        val permission = SessionLifecycle.PermissionRequested(CONTEXT, permission())
        dispatcher.observe(permission)
        dispatcher.observe(SessionLifecycle.TurnFinished(CONTEXT, TurnOutcome.Completed))
        dispatcher.observe(SessionLifecycle.TurnStarted(CONTEXT.copy(owner = SessionOwner("other"))))
        repeat(2) { dispatcher.observe(SessionLifecycle.TurnStarted(CONTEXT)) }
        repeat(2) { dispatcher.observe(permission) }
        repeat(2) { dispatcher.observe(SessionLifecycle.TurnFinished(CONTEXT, TurnOutcome.Completed)) }
        dispatcher.releaseTurn(SESSION, TURN)
        repeat(2) { dispatcher.observe(SessionLifecycle.Closed(CONTEXT)) }
        runCurrent()
        assertEquals(
            listOf("Opened", "TurnStarted", "PermissionRequested", "TurnFinished", "Closed"),
            hook.events.map { it::class.simpleName },
        )
    }

    @Test
    fun `closing one handle preserves a different owner's turn`() = runTest {
        val dispatcher = dispatcher(RecordingHook())
        val other = CONTEXT.copy(owner = SessionOwner("other"))
        dispatcher.observe(SessionLifecycle.Opened(CONTEXT))
        dispatcher.observe(SessionLifecycle.Opened(other))
        dispatcher.bindTurn(other)
        dispatcher.observe(SessionLifecycle.Closed(CONTEXT))
        assertEquals(other, dispatcher.context(SESSION, REQUEST, TURN))
        assertNull(dispatcher.beforePrompt(CONTEXT, "text"))
    }

    @Test
    fun `deny wins even when another hook fails and all hooks run concurrently`() = runTest {
        val started = CompletableDeferred<Unit>()
        val deny = object : SessionHook {
            override val isIntercepting = true
            override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
                started.await()
                return ToolHookVerdict.Deny("no")
            }
        }
        val failing = object : SessionHook {
            override val isIntercepting = true
            override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
                started.complete(Unit)
                error("private source text")
            }
        }
        val dispatcher = dispatcher(deny, failing).opened()
        assertEquals(ToolHookVerdict.Deny("no"), dispatcher.beforeTool(call()))
    }

    @Test
    fun `slow hooks share one timeout and force asking`() = runTest {
        val dispatcher = dispatcher(HangingHook(), HangingHook()).opened()
        assertIs<ToolHookVerdict.Ask>(dispatcher.beforeTool(call()))
        assertEquals(1_200, currentTime)
    }

    @Test
    fun `prompt and result failures add no text while tool failure asks`() = runTest {
        val hook = object : SessionHook {
            override val isIntercepting = true
            override suspend fun beforePrompt(context: SessionHookContext, text: String): String = error("source")
            override suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String = error("source")
            override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = error("source")
        }
        val dispatcher = dispatcher(hook).opened()
        assertNull(dispatcher.beforePrompt(CONTEXT, "private prompt"))
        assertNull(dispatcher.afterTool(call(), AgentToolResult("private result")))
        assertIs<ToolHookVerdict.Ask>(dispatcher.beforeTool(call()))
    }

    @Test
    fun `tool notes are bounded and disabled hooks are skipped`() = runTest {
        val hook = RecordingHook()
        val dispatcher = dispatcher(hook).opened()
        assertEquals(2_000, dispatcher.afterTool(call(), AgentToolResult("result"))?.length)
        hook.isIntercepting = false
        assertNull(dispatcher.afterTool(call(), AgentToolResult("result")))
        assertEquals(ToolHookVerdict.Continue, dispatcher.beforeTool(call()))
        assertEquals(0, hook.calls)
    }

    @Test
    fun `native callbacks without a facade turn still run hooks for their exact request`() = runTest {
        val hook = RecordingHook()
        val dispatcher = dispatcher(hook).opened()
        val native = call(CONTEXT.copy(turn = null)).copy(isNative = true)
        assertIs<ToolHookVerdict.Ask>(dispatcher.beforeTool(native))
        assertEquals(1, hook.calls)
        val unknown = native.copy(context = native.context.copy(request = RequestId("other")))
        assertEquals(ToolHookVerdict.Continue, dispatcher.beforeTool(unknown))
        assertEquals(1, hook.calls)
    }

    @Test
    fun `finished turns are forgotten only after both lifecycle and tool barrier`() {
        for (isBarrierFirst in listOf(false, true)) {
            val sessions = HookSessions()
            sessions.accept(SessionLifecycle.Opened(CONTEXT))
            sessions.bind(CONTEXT)
            if (isBarrierFirst) sessions.release(SESSION, TURN, isRejected = false)
            assertTrue(sessions.accept(SessionLifecycle.TurnStarted(CONTEXT)))
            assertTrue(sessions.accept(SessionLifecycle.TurnFinished(CONTEXT, TurnOutcome.Completed)))
            if (!isBarrierFirst) {
                assertEquals(1, sessions.turnCount)
                sessions.release(SESSION, TURN, isRejected = false)
            }
            assertEquals(0, sessions.turnCount)
            assertFalse(sessions.accept(SessionLifecycle.TurnStarted(CONTEXT)))
        }
        val sessions = HookSessions()
        sessions.accept(SessionLifecycle.Opened(CONTEXT))
        sessions.bind(CONTEXT)
        sessions.release(SESSION, TURN, isRejected = true)
        assertEquals(0, sessions.turnCount)
    }

    @Test
    fun `closed owners keep interception until the barrier then release tracking`() {
        for (isBarrierFirst in listOf(false, true)) {
            val sessions = HookSessions()
            sessions.accept(SessionLifecycle.Opened(CONTEXT))
            sessions.bind(CONTEXT)
            sessions.accept(SessionLifecycle.TurnStarted(CONTEXT))
            if (isBarrierFirst) sessions.release(SESSION, TURN, isRejected = false)
            sessions.accept(SessionLifecycle.Closed(CONTEXT))
            if (!isBarrierFirst) {
                assertEquals(CONTEXT, sessions.context(SESSION, REQUEST, TURN))
                sessions.release(SESSION, TURN, isRejected = false)
            }
            assertNull(sessions.context(SESSION, REQUEST, TURN))
            assertEquals(0, sessions.turnCount)
        }
    }

    @Test
    fun `caller cancellation propagates and cancels a suspended hook`() = runTest {
        var isCancelled = false
        val hook = object : SessionHook {
            override val isIntercepting = true
            override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = try {
                awaitCancellation()
            } finally {
                isCancelled = true
            }
        }
        val dispatcher = dispatcher(hook).opened()
        val call = async { dispatcher.beforeTool(call()) }
        runCurrent()
        call.cancel(CancellationException("caller stopped"))
        call.join()
        runCurrent()
        assertTrue(call.isCancelled)
        assertTrue(isCancelled)
        assertFalse(call.isActive)
    }
}

private class RecordingHook : SessionHook {
    override val isObserving = true
    override var isIntercepting = true
    val events = mutableListOf<SessionLifecycle>()
    var calls = 0
    override suspend fun observe(event: SessionLifecycle) {
        events += event
    }
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
        calls++
        return ToolHookVerdict.Ask("check")
    }
    override suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String = "x".repeat(3_000)
}

private class HangingHook : SessionHook {
    override val isIntercepting = true
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = awaitCancellation()
}

private fun TestScope.dispatcher(vararg hooks: SessionHook): ProfileSessionHooks {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val provider = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }
    return ProfileSessionHooks(lazy { hooks.toSet() }, provider, backgroundScope)
}

private fun ProfileSessionHooks.opened(): ProfileSessionHooks = apply {
    observe(SessionLifecycle.Opened(CONTEXT))
    bindTurn(CONTEXT)
}

private val SESSION = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
private val REQUEST = RequestId("request")
private val TURN = TurnId("turn")
private val CONTEXT = SessionHookContext(SESSION, null, REQUEST, TURN, SessionOwner("handle"))
private fun call(context: SessionHookContext = CONTEXT): HookedToolCall =
    HookedToolCall(context, "read", AgentToolAction.Read, JsonObject(emptyMap()))
private fun permission(): PermissionRequest = PermissionRequest(
    PermissionRequestId("permission"),
    TURN,
    "Read",
    listOf(PermissionOption(PermissionOptionId("yes"), "Yes")),
)
