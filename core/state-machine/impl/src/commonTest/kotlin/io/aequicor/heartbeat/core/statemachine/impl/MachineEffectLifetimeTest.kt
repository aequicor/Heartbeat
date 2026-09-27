package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MachineEffectLifetimeTest {
    private val runtime = MachineRuntime()

    @BeforeTest
    fun setUp() = LogCapture().install()

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun `retained feedback from an exited state cannot complete its replacement`() = runTest {
        val effects = RetainedLoads()
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), effects)
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()
        val oldFeedback = effects.feedback.getValue("c1")

        machine.send(ChatIntent.Public.Open("c2"))
        runCurrent()

        assertEquals(SendResult.Ignored, oldFeedback.send(ChatIntent.Internal.Loaded("c1")))
        assertEquals(ChatState.Loading("c2"), machine.state.value)
        assertEquals(
            SendResult.Accepted,
            effects.feedback.getValue("c2").send(ChatIntent.Internal.Loaded("c2")),
        )
        assertEquals(ChatState.Ready("c2"), machine.state.value)
    }

    @Test
    fun `retained feedback remains valid after stay`() = runTest {
        val effects = FakeEffects()
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), effects)
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()
        effects.loads.getValue("c1").complete(ChatIntent.Internal.Loaded("c1"))
        runCurrent()
        machine.send(ChatIntent.Public.SendPrompt("hello"))
        runCurrent()
        val feedback = requireNotNull(effects.generationScope)

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Internal.Draft("draft")))
        assertEquals(SendResult.Accepted, feedback.send(ChatIntent.Internal.Completed))
        assertEquals(ChatState.Ready("c1"), machine.state.value)
    }

    @Test
    fun `non-cancellable cleanup cannot send feedback after leaving its state`() = runTest {
        val cleanupResults = mutableMapOf<String, SendResult>()
        val effects = EffectHandler<ChatEffect, ChatIntent> { effect, feedback ->
            val load = assertIs<ChatEffect.Load>(effect)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupResults[load.chatId] = feedback.send(ChatIntent.Internal.Loaded(load.chatId))
                }
            }
        }
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), effects)
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()

        machine.send(ChatIntent.Public.Open("c2"))
        runCurrent()

        assertEquals(SendResult.Ignored, cleanupResults.getValue("c1"))
        assertEquals(ChatState.Loading("c2"), machine.state.value)
    }

    @Test
    fun `feedback queued behind a transition is checked after acquiring the mutex`() = runTest {
        val effects = RetainedLoads()
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), effects)
        machine.send(ChatIntent.Public.Open("c1"))
        runCurrent()
        val oldFeedback = effects.feedback.getValue("c1")
        var queuedResult: SendResult? = null
        var queueFeedback = true
        // The incoming-intent log runs inside the dispatch mutex, before the old state scope is cancelled.
        val sink = LogSink { _, tag, _, message ->
            if (queueFeedback && tag == "SM/chat" && message == "← Open (own feature)") {
                queueFeedback = false
                launch(start = CoroutineStart.UNDISPATCHED) {
                    queuedResult = oldFeedback.send(ChatIntent.Internal.Loaded("c1"))
                }
                assertNull(queuedResult)
            }
        }
        Log.init(isDebug = true, sinks = listOf(sink))

        machine.send(ChatIntent.Public.Open("c2"))
        runCurrent()

        assertEquals(SendResult.Ignored, queuedResult)
        assertEquals(ChatState.Loading("c2"), machine.state.value)
    }

    private class RetainedLoads : EffectHandler<ChatEffect, ChatIntent> {
        val feedback = mutableMapOf<String, EffectScope<ChatIntent>>()

        override suspend fun handle(effect: ChatEffect, machine: EffectScope<ChatIntent>) {
            val load = assertIs<ChatEffect.Load>(effect)
            feedback[load.chatId] = machine
            awaitCancellation()
        }
    }
}
