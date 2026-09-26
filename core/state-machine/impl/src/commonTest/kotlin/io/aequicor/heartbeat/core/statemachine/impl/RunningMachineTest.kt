package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunningMachineTest {
    private val logs = LogCapture()
    private val runtime = MachineRuntime()
    private val effects = FakeEffects()

    @BeforeTest
    fun setUp() = logs.install()

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    private fun TestScope.launchChat(scope: FakeScope = FakeScope(backgroundScope)) =
        runtime.launch(chatSpec(), scope, effects)

    @Test
    fun `starts in the initial state`() = runTest {
        val machine = launchChat()

        assertEquals(ChatState.Idle, machine.state.value)
        assertEquals(listOf("started in Idle (initial), scope app/profile/chat"), logs.messages(level = LogLevel.INFO))
    }

    @Test
    fun `transition launches the effect and its result comes back as an intent`() = runTest {
        val machine = launchChat()

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Open("c1")))
        assertEquals(ChatState.Loading("c1"), machine.state.value)
        runCurrent()
        effects.loads.getValue("c1").complete(ChatIntent.Internal.Loaded("c1"))
        runCurrent()

        assertEquals(ChatState.Ready("c1"), machine.state.value)
        assertEquals(
            listOf(
                "← Open (own feature)",
                "Idle --Open--> Loading",
                "effect Load started",
                "← Loaded (effect Load)",
                "Loading --Loaded--> Ready",
                "effect Load completed",
            ),
            logs.messages().dropWhile { !it.startsWith("← Open") },
        )
    }

    @Test
    fun `intent without transition is ignored and logged as warning`() = runTest {
        val machine = launchChat()

        assertEquals(SendResult.Ignored, machine.send(ChatIntent.Public.Cancel))

        assertEquals(ChatState.Idle, machine.state.value)
        assertEquals(listOf("ignored Cancel in Idle: no transition"), logs.messages(level = LogLevel.WARNING))
    }

    @Test
    fun `leaving the state cancels its effects and drops their late results`() = runTest {
        val machine = generating()

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Cancel))
        runCurrent()

        assertEquals(ChatState.Ready("c1"), machine.state.value)
        assertTrue("effect Generate cancelled" in logs.messages(level = LogLevel.DEBUG))
        effects.generation.complete(Unit)
        runCurrent()
        assertEquals(ChatState.Ready("c1"), machine.state.value)
    }

    @Test
    fun `stay keeps running effects`() = runTest {
        val machine = generating()

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Internal.Draft("x")))
        effects.generation.complete(Unit)
        runCurrent()

        assertEquals(ChatState.Ready("c1"), machine.state.value)
        assertTrue("Generating --Draft--> (no change)" in logs.messages(level = LogLevel.DEBUG))
        assertTrue("effect Generate completed" in logs.messages(level = LogLevel.DEBUG))
    }

    @Test
    fun `outputs reach subscribers`() = runTest {
        val machine = generating()
        val outputs = mutableListOf<ChatOutput>()
        val collector = launch { machine.outputs.toList(outputs) }
        runCurrent()

        effects.generation.complete(Unit)
        runCurrent()

        assertEquals(listOf<ChatOutput>(ChatOutput.Generated), outputs)
        assertTrue("→ output Generated (subscribers: 1)" in logs.messages())
        collector.cancel()
    }

    @Test
    fun `failed effect is logged and mapped to an intent`() = runTest {
        val failing = EffectHandler<ChatEffect, ChatIntent> { _, _ -> error("boom") }
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), failing)

        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()

        assertEquals(ChatState.Error("boom"), machine.state.value)
        val failure = logs.records.single { it.level == LogLevel.ERROR }
        assertEquals("effect Load failed", failure.message)
        assertIs<IllegalStateException>(failure.error)
        assertTrue("← Failed (failure of effect Load)" in logs.messages())
    }

    @Test
    fun `closing the scope stops the machine`() = runTest {
        val scope = FakeScope(backgroundScope)
        val machine = launchChat(scope)

        scope.close()

        assertEquals(SendResult.NotRunning, machine.send(ChatIntent.Public.Open("c1")))
        assertEquals(ChatState.Idle, machine.state.value)
        assertTrue("stopped in Idle, scope app/profile/chat closed" in logs.messages(level = LogLevel.INFO))
        assertTrue(logs.messages(level = LogLevel.WARNING).single().endsWith("dropped: machine is not running"))
    }

    @Test
    fun `persistent machine relaunches the effect of a restored state`() = runTest {
        val first = FakeScope(backgroundScope)
        runtime.launch(chatSpec(persist = true), first, effects).send(ChatIntent.Public.Open("c1"))
        val saved = first.savedState.snapshot()
        first.close()
        val restoredEffects = FakeEffects()

        val restoredScope = FakeScope(backgroundScope, FakeSavedState(saved))
        val restored = runtime.launch(chatSpec(persist = true), restoredScope, restoredEffects)
        runCurrent()
        restoredEffects.loads.getValue("c1").complete(ChatIntent.Internal.Loaded("c1"))
        runCurrent()

        assertEquals(ChatState.Ready("c1"), restored.state.value)
        val info = logs.messages(level = LogLevel.INFO)
        assertTrue(info.any { it.startsWith("started in Loading (restored from saved Loading, relaunching [Load])") })
    }

    @Test
    fun `restore maps a transient state to a stable one`() = runTest {
        val first = FakeScope(backgroundScope)
        val machine = runtime.launch(chatSpec(persist = true), first, effects)
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()
        effects.loads.getValue("c1").complete(ChatIntent.Internal.Loaded("c1"))
        runCurrent()
        machine.send(ChatIntent.Public.SendPrompt("hi"))
        val saved = first.savedState.snapshot()
        first.close()

        val restoredScope = FakeScope(backgroundScope, FakeSavedState(saved))
        val restored = runtime.launch(chatSpec(persist = true), restoredScope, effects)

        assertEquals(ChatState.Ready("c1"), restored.state.value)
    }

    @Test
    fun `saved state that is no longer declared falls back to initial`() = runTest {
        val first = FakeScope(backgroundScope)
        runtime.launch(chatSpec(persist = true), first, effects).send(ChatIntent.Public.Open("c1"))
        val saved = first.savedState.snapshot()
        first.close()
        val shrunk = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
            state<ChatState.Idle>()
            persist(ChatState.serializer())
        }

        val restored = runtime.launch(shrunk, FakeScope(backgroundScope, FakeSavedState(saved)), EffectHandler.None)

        assertEquals(ChatState.Idle, restored.state.value)
        assertTrue(logs.messages(level = LogLevel.INFO).any { "is not declared any more, fell back to initial" in it })
    }

    @Test
    fun `any transition goes through the engine`() = runTest {
        val machine = generating()

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Reset))
        runCurrent()

        assertEquals(ChatState.Idle, machine.state.value)
        assertTrue("effect Generate cancelled" in logs.messages(level = LogLevel.DEBUG))
    }

    @Test
    fun `re-entering the same state cancels its effects`() = runTest {
        val machine = launchChat()
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Open("c2")))
        runCurrent()
        effects.loads.getValue("c1").complete(ChatIntent.Internal.Loaded("c1"))
        runCurrent()
        assertEquals(ChatState.Loading("c2"), machine.state.value)

        effects.loads.getValue("c2").complete(ChatIntent.Internal.Loaded("c2"))
        runCurrent()
        assertEquals(ChatState.Ready("c2"), machine.state.value)
        assertTrue("Loading --Open--> Loading" in logs.messages(level = LogLevel.INFO))
    }

    @Test
    fun `cancellation exception from an active effect is a failure`() = runTest {
        val timingOut = EffectHandler<ChatEffect, ChatIntent> { _, _ -> withTimeout(1) { awaitCancellation() } }
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), timingOut)

        machine.send(ChatIntent.Public.Open("c1"))
        advanceTimeBy(10)
        runCurrent()

        assertIs<ChatState.Error>(machine.state.value)
        val failure = logs.records.single { it.level == LogLevel.ERROR }
        assertEquals("effect Load failed: cancelled from inside", failure.message)
    }

    @Test
    fun `output without subscribers is reported as dropped`() = runTest {
        val machine = generating()

        effects.generation.complete(Unit)
        runCurrent()

        assertEquals(listOf("→ output Generated dropped (subscribers: 0)"), logs.messages(level = LogLevel.WARNING))
        assertEquals(ChatState.Ready("c1"), machine.state.value)
    }

    private suspend fun TestScope.generating() = launchChat().also { machine ->
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()
        effects.loads.getValue("c1").complete(ChatIntent.Internal.Loaded("c1"))
        runCurrent()
        machine.send(ChatIntent.Public.SendPrompt("hi"))
        runCurrent()
        assertEquals(ChatState.Generating("c1"), machine.state.value)
    }
}
