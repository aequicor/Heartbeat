package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
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
    fun `temporary suspension drains handoffs and reopens only after cleanup`() = runTest {
        val gate = gate()
        val reply = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val native = async {
            gate.admitted(binding) {
                started.complete(Unit)
                reply.await()
            }
        }
        started.await()
        var isCleaned = false
        val cleanup = async {
            gate.suspended(binding) {
                assertTrue(native.isCompleted)
                assertFailsWith<IllegalStateException> { gate.check(binding) }
                isCleaned = true
            }
        }
        runCurrent()
        assertFalse(isCleaned)
        assertFailsWith<IllegalStateException> { gate.check(binding) }
        reply.complete(Unit)
        native.await()
        cleanup.await()
        assertTrue(isCleaned)
        assertTrue(gate.admitted(binding) { true })
        assertFailsWith<IllegalStateException> { gate.awaitAdmissions(binding) }
    }

    @Test
    fun `nested suspension and permanent retirement remain independent`() = runTest {
        val gate = gate()
        gate.suspended(binding) {
            gate.suspended(binding) { Unit }
            assertFailsWith<IllegalStateException> { gate.check(binding) }
            gate.fence(binding) { Unit }
        }
        assertFailsWith<IllegalStateException> { gate.check(binding) }
        gate.awaitAdmissions(binding)
    }

    @Test
    fun `cancelled temporary drain retains the unresolved native ticket`() = runTest {
        val gate = gate()
        val started = CompletableDeferred<Unit>()
        val native = async {
            gate.admitted(binding) {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        native.cancel()
        native.join()
        val cleanup = backgroundScope.async { gate.suspended(binding) { error("Must not claim drained") } }
        runCurrent()
        assertFalse(cleanup.isCompleted)
        cleanup.cancel()
        cleanup.join()
        val retry = backgroundScope.async { gate.suspended(binding) { error("Must retain pending ticket") } }
        runCurrent()
        assertFalse(retry.isCompleted)
    }

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

    @Test
    fun `native entry consumes the exact request once even when its RPC fails`() = runTest {
        val session = SessionRef(EngineId("test"), SessionSourceId("native"), "session")
        val request = RequestId("original")
        var consumed = false
        val claims = object : SessionExecutionAuthority by provider {
            override suspend fun enterPrompt(
                binding: SessionExecutionBinding,
                session: SessionRef,
                request: RequestId,
            ): Boolean {
                if (consumed || request.value != "original" || session.source.value != "native") return false
                consumed = true
                return true
            }
        }
        val gate = SessionExecutionAdmissions(lazyOf(setOf(claims)))
        assertFailsWith<IllegalStateException> {
            gate.admitted(binding, session to RequestId("replacement")) { error("Must not enter") }
        }
        assertFalse(consumed)
        assertFailsWith<IllegalArgumentException> {
            gate.admitted(binding, session to request) { throw IllegalArgumentException("Lost native acknowledgement") }
        }
        assertTrue(consumed)
        assertFailsWith<IllegalStateException> {
            gate.admitted(binding, session to request) { error("Must not replay") }
        }
    }

    private fun errorPersistence(): Nothing = throw IllegalArgumentException("storage unavailable")
}
