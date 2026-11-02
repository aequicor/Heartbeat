package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExecutionAdmissionsTest {
    private val binding = SessionExecutionBinding(SessionExecutionInstance("test", "incarnation-1"))
    private var isPermitted = true
    private val provider = object : SessionExecutionAuthority {
        override val provider = "test"
        override suspend fun permits(binding: SessionExecutionBinding) = isPermitted
    }
    private fun gate() = SessionExecutionAdmissions(lazyOf(setOf(provider)))

    @Test
    fun `retirement fences new work but waits for an already admitted late native result`() = runTest {
        val gate = gate()
        val entered = CompletableDeferred<Unit>()
        val nativeReply = CompletableDeferred<Unit>()
        val late = async {
            gate.admitted(binding) {
                entered.complete(Unit)
                nativeReply.await()
                assertFailsWith<IllegalStateException> { gate.check(binding) }
                "late result cleaned up"
            }
        }
        entered.await()
        gate.fence(binding) { isPermitted = false }
        val draining = async { gate.awaitAdmissions(binding) }
        runCurrent()
        assertFalse(draining.isCompleted)
        assertFailsWith<IllegalStateException> { gate.admitted(binding) { error("must not execute") } }
        nativeReply.complete(Unit)
        assertEquals("late result cleaned up", late.await())
        draining.await()
    }

    @Test
    fun `failed durable retirement remains closed and cannot claim a drained durable fence`() = runTest {
        val gate = gate()
        assertFailsWith<IllegalArgumentException> { gate.fence(binding) { errorPersistence() } }
        assertFailsWith<IllegalStateException> { gate.admitted(binding) { Unit } }
        assertFailsWith<IllegalStateException> { gate.awaitAdmissions(binding) }
        gate.fence(binding) { isPermitted = false }
        gate.awaitAdmissions(binding)
    }

    @Test
    fun `restart asks canonical authority and unavailable or duplicate providers deny`() = runTest {
        isPermitted = false
        assertFailsWith<IllegalStateException> { gate().admitted(binding) { Unit } }
        val missing = SessionExecutionAdmissions(lazyOf(emptySet()))
        assertFailsWith<IllegalStateException> { missing.admitted(binding) { Unit } }
        isPermitted = true
        val duplicate = object : SessionExecutionAuthority by provider {}
        val ambiguous = SessionExecutionAdmissions(lazyOf(setOf(provider, duplicate)))
        assertFailsWith<IllegalStateException> { ambiguous.admitted(binding) { Unit } }
    }

    @Test
    fun `a replacement remains usable while cancelled native handoff stays unconfirmed`() = runTest {
        val gate = gate()
        val entered = CompletableDeferred<Unit>()
        val pending = async {
            gate.admitted(binding) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        gate.fence(binding) { Unit }
        pending.cancel()
        pending.join()
        val draining = backgroundScope.async { gate.awaitAdmissions(binding) }
        runCurrent()
        assertFalse(draining.isCompleted)
        val replacement = binding.copy(instance = binding.instance.copy(id = "incarnation-2"))
        assertTrue(gate.admitted(replacement) { true })
    }

    private fun errorPersistence(): Nothing = throw IllegalArgumentException("storage unavailable")
}
