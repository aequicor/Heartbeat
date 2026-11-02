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
class SessionExecutionOpeningTest {
    private val binding = SessionExecutionBinding(SessionExecutionInstance("host", "first"))
    private val ref = sessionRef("new-native")
    private val provider = object : SessionExecutionAuthority {
        override val provider = "host"
        override suspend fun permits(binding: SessionExecutionBinding) = true
    }

    @Test
    fun `reservation fences new unbound RPC and waits for old admission before claiming native authority`() = runTest {
        val store = ExecutionTestBindings()
        val access = SessionExecutionAccess(store, SessionExecutionAdmissions(lazyOf(setOf(provider))))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            access.admitted(ref, null) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var isNativeStarted = false
        val create = async {
            access.creating(ref.engine, "opening", binding) {
                isNativeStarted = true
                access.record(ref, it)
            }
        }
        runCurrent()
        assertFalse(isNativeStarted)
        assertEquals(1, store.pending().size)
        assertFailsWith<IllegalStateException> { access.admitted(ref, null) { error("must not send") } }
        release.complete(Unit)
        old.await()
        create.await()
        assertTrue(isNativeStarted)
        assertEquals(binding, store.find(ref))
        assertFailsWith<IllegalStateException> { access.admitted(ref, null) { error("stale handle must not send") } }
    }

    @Test
    fun `restart with unknown native ref cannot claim an empty admission set proves cleanup`() = runTest {
        val store = ExecutionTestBindings()
        store.reserve(SessionExecutionOpening("lost-ack", ref.engine, binding))
        val restarted = SessionExecutionAccess(store, SessionExecutionAdmissions(lazyOf(setOf(provider))))
        assertFailsWith<IllegalStateException> { restarted.resolve(ref, null, null) }
        restarted.fence(binding) { Unit }
        assertFailsWith<IllegalStateException> { restarted.awaitAdmissions(binding) }
        assertTrue(restarted.references(binding).isEmpty())
        assertEquals(1, store.pending().size)
    }
}
