package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MachineAdmissionTest {
    @Test
    fun `admission holds the transition lock through delivery`() = runTest {
        val machine = MachineRuntime().launch(chatSpec(), FakeScope(backgroundScope), FakeEffects())
        val delivering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val admitted = async {
            machine.admit { state ->
                assertEquals(ChatState.Idle, state)
                delivering.complete(Unit)
                release.await()
                assertEquals(state, machine.state.value)
                true
            }
        }
        delivering.await()
        val writer = async { machine.send(ChatIntent.Public.Open("c1")) }
        runCurrent()
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        assertTrue(admitted.await())
        assertEquals(SendResult.Accepted, writer.await())
    }

    @Test
    fun `admission sees a completed writer and releases the lock on cancellation`() = runTest {
        val machine = MachineRuntime().launch(chatSpec(), FakeScope(backgroundScope), FakeEffects())
        machine.send(ChatIntent.Public.Open("c1"))
        val entered = CompletableDeferred<Unit>()
        val admission = async {
            machine.admit { state ->
                assertEquals(ChatState.Loading("c1"), state)
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                true
            }
        }
        entered.await()
        admission.cancel()
        admission.join()
        assertTrue(machine.admit { it == ChatState.Loading("c1") })
    }
}
