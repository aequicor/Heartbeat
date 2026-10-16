package io.aequicor.heartbeat.core.mvi

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeartbeatStoreFactoryTest {
    @AfterTest
    fun cleanup() = Log.init(isDebug = false)

    @Test
    fun `alternating input intents stay quiet in debug and retain type-only trace logs`() = runTest {
        val records = mutableListOf<Pair<LogLevel, String>>()
        val sink = LogSink { level, _, _, message -> records += level to message }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = HeartbeatStoreFactory(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        for (trace in listOf(false, true)) {
            records.clear()
            Log.init(isDebug = true, isTrace = trace, sinks = listOf(sink))
            val store = factory.create<State, MVIIntent, Action>(
                name = "InputTest",
                initial = State(),
                onError = { copy(error = true) },
            ) {
                reduce { intent ->
                    if (intent is Intent) updateState { copy(text = intent.text) }
                }
            }
            store.subscribeAndTest {
                repeat(10) {
                    Intent("private prompt $it", fail = false) resultsIn { advanceUntilIdle() }
                    CaretMoved(it) resultsIn { advanceUntilIdle() }
                }
                assertEquals("private prompt 9", states.value.text)
            }
            val intentRecords = records.filter { it.second.startsWith("intent ") }
            val expected = if (trace) {
                List(10) {
                    listOf(LogLevel.VERBOSE to "intent Intent", LogLevel.VERBOSE to "intent CaretMoved")
                }.flatten()
            } else {
                emptyList()
            }
            assertEquals(expected, intentRecords)
            assertTrue(records.any { it == LogLevel.INFO to "started" })
            assertFalse(records.any { "private prompt" in it.second })
        }
    }

    @Test
    fun `factory recovers failed intents and logs types without user content`() = runTest {
        val records = mutableListOf<String>()
        Log.init(true, sinks = listOf(LogSink { _, _, _, message -> records += message }))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = HeartbeatStoreFactory(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        val store = factory.create<State, Intent, Action>("FactoryTest", State(), onError = { copy(error = true) }) {
            reduce { intent ->
                check(!intent.fail) { "test failure" }
                updateState { State(text = intent.text) }
            }
        }
        store.subscribeAndTest {
            Intent("private prompt", fail = true) resultsIn { advanceUntilIdle() }
            assertTrue(states.value.error)
            Intent("private prompt", fail = false) resultsIn { advanceUntilIdle() }
            assertEquals(State("private prompt"), states.value)
        }
        assertTrue(records.any { "store operation failed" in it })
        assertFalse(records.any { "private prompt" in it })
    }

    @Test
    fun `current state starts from the initial one and keeps the last committed state without a subscriber`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = HeartbeatStoreFactory(object : DispatcherProvider {
                override val main = dispatcher
                override val default = dispatcher
                override val io = dispatcher
            })
            val screen = factory.createScreen<State, Intent, Action>("CurrentTest", State("derived"), { this }) {
                reduce { intent -> updateState { State(text = intent.text) } }
            }
            assertEquals(State("derived"), screen.current.value)
            screen.store.subscribeAndTest {
                Intent("edited", fail = false) resultsIn { advanceUntilIdle() }
                assertEquals(states.value, screen.current.value)
            }
            assertEquals(State("edited"), screen.current.value)
        }

    private data class State(val text: String = "", val error: Boolean = false) : MVIState
    private data class Intent(val text: String, val fail: Boolean) : MVIIntent
    private data class CaretMoved(val caret: Int) : MVIIntent
    private sealed interface Action : MVIAction
}
