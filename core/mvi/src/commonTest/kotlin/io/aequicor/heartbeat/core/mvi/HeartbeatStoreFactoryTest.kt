package io.aequicor.heartbeat.core.mvi

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
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

    private data class State(val text: String = "", val error: Boolean = false) : MVIState
    private data class Intent(val text: String, val fail: Boolean) : MVIIntent
    private sealed interface Action : MVIAction
}
