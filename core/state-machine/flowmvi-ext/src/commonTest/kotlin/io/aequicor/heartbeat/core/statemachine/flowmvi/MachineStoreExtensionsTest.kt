package io.aequicor.heartbeat.core.statemachine.flowmvi

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MachineStoreExtensionsTest {
    private val records = mutableListOf<Pair<LogLevel, String>>()
    private val machine = FakeMachine()

    @BeforeTest
    fun setUp() {
        val sink = LogSink { level, tag, _, message ->
            if (tag == "MVI/Screen") records += level to message
        }
        Log.init(isDebug = true, isTrace = true, sinks = listOf(sink))
    }

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    private fun screenStore() = store<ScreenState, ScreenIntent, ScreenAction>(ScreenState.Loading) {
        configure { name = "Screen" }
        reflect(machine, onOutput = { output ->
            when (output) {
                Output.Done -> action(ScreenAction.ShowDone)
            }
        }) { machineState ->
            when (machineState) {
                Machine.Idle -> ScreenState.Loading
                is Machine.Ready -> ScreenState.Content(machineState.text)
            }
        }
        reduce { intent ->
            when (intent) {
                ScreenIntent.Clicked -> sendTo(machine, Intent.Go) { action(ScreenAction.ShowRejected) }
            }
        }
    }

    @Test
    fun `store state follows the machine`() = runTest {
        screenStore().subscribeAndTest {
            advanceUntilIdle()
            assertEquals(ScreenState.Loading, states.value)

            machine.state.value = Machine.Ready("hi")
            advanceUntilIdle()

            assertEquals(ScreenState.Content("hi"), states.value)
            assertTrue(records.any { it == LogLevel.VERBOSE to "machine m: Ready → store state Content" })
        }
    }

    @Test
    fun `normal debug follows machine updates without logging each projection`() = runTest {
        val sink = LogSink { level, tag, _, message ->
            if (tag == "MVI/Screen") records += level to message
        }
        Log.init(isDebug = true, sinks = listOf(sink))
        screenStore().subscribeAndTest {
            advanceUntilIdle()
            machine.state.value = Machine.Ready("streamed")
            advanceUntilIdle()

            assertEquals(ScreenState.Content("streamed"), states.value)
            assertTrue(records.isEmpty())
        }
    }

    @Test
    fun `machine outputs become store actions`() = runTest {
        screenStore().subscribeAndTest {
            advanceUntilIdle()
            machine.outputs.emit(Output.Done)

            assertEquals(ScreenAction.ShowDone, actions.first())
            assertTrue(records.any { it == LogLevel.VERBOSE to "machine m: output Done" })
        }
    }

    @Test
    fun `intent is sent to the machine`() = runTest {
        screenStore().subscribeAndTest {
            ScreenIntent.Clicked resultsIn { advanceUntilIdle() }

            assertEquals(listOf<Intent>(Intent.Go), machine.sent)
            assertTrue(records.none { it.first == LogLevel.WARNING })
        }
    }

    @Test
    fun `rejected intent is reported to the store and logged`() = runTest {
        machine.result = SendResult.Ignored
        screenStore().subscribeAndTest {
            ScreenIntent.Clicked resultsIn ScreenAction.ShowRejected

            val warnings = records.filter { it.first == LogLevel.WARNING }
            assertEquals(listOf(LogLevel.WARNING to "→ m: Go rejected (Ignored)"), warnings)
        }
    }

    private sealed interface Machine : MachineState {
        data object Idle : Machine
        data class Ready(val text: String) : Machine
    }

    private sealed interface Intent : MachineIntent {
        data object Go : Intent
    }

    private sealed interface Output : MachineOutput {
        data object Done : Output
    }

    private class FakeMachine : MachineRef<Machine, Intent, Output> {
        override val name = "m"
        override val state = MutableStateFlow<Machine>(Machine.Idle)
        override val outputs = MutableSharedFlow<Output>(extraBufferCapacity = 8)
        val sent = mutableListOf<Intent>()
        var result = SendResult.Accepted

        override suspend fun send(intent: Intent): SendResult {
            sent += intent
            return result
        }
    }

    private sealed interface ScreenState : MVIState {
        data object Loading : ScreenState
        data class Content(val text: String) : ScreenState
    }

    private sealed interface ScreenIntent : MVIIntent {
        data object Clicked : ScreenIntent
    }

    private sealed interface ScreenAction : MVIAction {
        data object ShowDone : ScreenAction
        data object ShowRejected : ScreenAction
    }
}
