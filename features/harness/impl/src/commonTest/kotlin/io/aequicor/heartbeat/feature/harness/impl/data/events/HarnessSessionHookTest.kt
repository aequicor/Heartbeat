package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
import io.aequicor.heartbeat.feature.harness.api.script.on
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessDispatchFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessHookDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessSessionHookTest {
    @Test
    fun `disabled contribution does not resolve lazy owners`() = runTest {
        val runtime = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val hook = HarnessSessionHook(
            HarnessEventGate(),
            lazy { error("Proof cache created while off") },
            HarnessSessionHookDispatchers(
                lazy { error("Hooks created while off") },
                lazy { error("Events while off") },
            ),
            lazy { error("Origins created while off") },
            CLOCK,
        )
        assertFalse(hook.isObserving)
        assertFalse(hook.isIntercepting)
        assertNull(hook.beforePrompt(runtime.context, "private"))
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(runtime.call))
        hook.observe(SessionLifecycle.Opened(runtime.context))
    }

    @Test
    fun `direct hook primes proof before Opened and Closed cannot revoke accepted context`() = runTest {
        val fixture = HookFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.runtime.onEvaluate = { script -> script.hooks.beforeTool { ToolHookVerdict.Deny("denied") } }
        fixture.runtime.activate()
        assertFalse(fixture.proofs.isResolved(fixture.runtime.context.session))
        assertEquals(ToolHookVerdict.Deny("denied"), fixture.hook.beforeTool(fixture.runtime.call))
        fixture.hook.observe(SessionLifecycle.Closed(fixture.runtime.context))
        assertEquals(ToolHookVerdict.Deny("denied"), fixture.hook.beforeTool(fixture.runtime.call))
    }

    @Test
    fun `unknown project still preserves a certain profile deny after bounded preparation`() = runTest {
        val fixture = HookFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.runtime.onEvaluate = { script -> script.hooks.beforeTool { ToolHookVerdict.Deny("denied") } }
        fixture.runtime.activate()
        fixture.addUnknownProject()
        fixture.prepare = { awaitCancellation() }
        val call = fixture.runtime.call.copy(
            context = fixture.runtime.context.copy(workspace = WorkspaceRef("unknown")),
        )
        assertEquals(ToolHookVerdict.Deny("denied"), fixture.hook.beforeTool(call))
        assertEquals(200L, testScheduler.currentTime)
        assertFalse(fixture.proofs.isResolved(call.context.session))
    }

    @Test
    fun `unknown project requires Ask while text hooks fail open`() = runTest {
        val fixture = HookFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.addUnknownProject()
        val call = fixture.runtime.call.copy(
            context = fixture.runtime.context.copy(workspace = WorkspaceRef("unknown")),
        )
        assertIs<ToolHookVerdict.Ask>(fixture.hook.beforeTool(call))
        assertNull(fixture.hook.beforePrompt(call.context, "private"))
    }

    @Test
    fun `lifecycle envelopes carry exact request ancestry without contaminating next request`() = runTest {
        val fixture = HookFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<HarnessCallOrigin?>()
        fixture.runtime.onEvaluate = { script ->
            script.events.on<SessionEvent> { observed += currentCoroutineContext()[HarnessOriginContext]?.origin }
        }
        fixture.runtime.activate()
        val request = RequestId("first")
        val context = fixture.runtime.context.copy(request = request)
        val origin = HarnessCallOrigin(sendChain = mapOf(HarnessId("owner") to 2))
        fixture.origins.register(context.session, request, origin)
        fixture.hook.observe(SessionLifecycle.TurnStarted(context))
        fixture.hook.observe(SessionLifecycle.Closed(context))
        fixture.hook.observe(SessionLifecycle.TurnStarted(context.copy(request = RequestId("second"))))
        runCurrent()
        assertEquals(listOf<HarnessCallOrigin?>(origin, origin, HarnessCallOrigin()), observed)
    }

    @Test
    fun `closing gate rejects already queued events before author callback starts`() = runTest {
        val fixture = HookFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.runtime.onEvaluate = { script -> script.events.on<SessionEvent> { calls++ } }
        fixture.runtime.activate()
        fixture.hook.observe(SessionLifecycle.Opened(fixture.runtime.context))
        fixture.gate.close()
        runCurrent()
        assertEquals(0, calls)
        assertFalse(fixture.hook.isObserving)
    }
}

private class HookFixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    val runtime = HarnessDispatchFixture(scope, dispatcher)
    val gate = HarnessEventGate().apply { open() }
    val origins = HarnessRequestOrigins()
    var library = HarnessState.Ready(listOf(HarnessEntry(runtime.request.harness)), isRuntimeAvailable = true)
    val proofs = HarnessSessionProofs(
        { if (gate.isEnabled) library else HarnessState.Idle(isSuspended = true) },
        { WorktreeState.Ready() },
        { null },
    )
    var prepare: suspend (SessionHookContext) -> Unit = {}
    val hook = HarnessSessionHook(
        gate,
        lazyOf(proofs),
        HarnessSessionHookDispatchers(
            lazy { HarnessHookDispatch(runtime.runtime, proofs) },
            lazy { HarnessEventDispatch(runtime.runtime, proofs) },
            { prepare(it) },
        ),
        lazyOf(origins),
        CLOCK,
    )

    fun addUnknownProject() {
        val project = runtime.request.harness.copy(
            id = HarnessId("project"),
            name = HarnessName("project"),
            scope = HarnessScope.Projects(setOf(WorkspaceRef("project"))),
        )
        library = library.copy(harnesses = library.harnesses + HarnessEntry(project))
    }
}

private val CLOCK = object : Clock {
    override fun now() = Instant.fromEpochSeconds(1_000)
}
