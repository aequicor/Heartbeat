@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiStoredProcessStopTest {
    @Test
    fun `PID reuse during inspection marker write never adopts foreign descendants`() = runTest {
        val original = StopProcess(1)
        val reused = StopProcess(1, "2026-10-07T00:00:00Z")
        val foreign = StopProcess(2)
        reused.observed = listOf(foreign)
        var current = original
        assertTrue(
            stopper { if (it == 1L) current else foreign }.stop(Owner, {
                current = reused
                true
            }) {
                assertTrue(it.observedChildren.isEmpty())
                it
            },
        )
        assertEquals(0, reused.signals)
        assertEquals(0, foreign.signals)
    }

    @Test
    fun `PID reuse during enumeration rejects all newly discovered children`() = runTest {
        val original = StopProcess(1)
        val reused = StopProcess(1, "2026-10-07T00:00:00Z")
        val foreign = StopProcess(2)
        var current = original
        original.observed = listOf(foreign)
        original.onEnumerate = { current = reused }
        var writes = 0
        assertFalse(
            stopper { if (it == 1L) current else foreign }.stop(Owner, { true }) {
                writes++
                it
            },
        )
        assertEquals(0, writes)
        assertEquals(0, original.signals)
        assertEquals(0, reused.signals)
        assertEquals(0, foreign.signals)
    }

    @Test
    fun `absent or reused PID is never signalled and unknown start time is not proof`() = runTest {
        assertTrue(stopper { null }.stop(Owner, { true }) { it })
        val reused = StopProcess(1, "2026-10-07T00:00:00Z")
        assertTrue(stopper { reused }.stop(Owner, { true }) { it })
        assertEquals(0, reused.signals)
        reused.startedAt = null
        var inspections = 0
        assertFalse(
            stopper { reused }.stop(Owner, {
                inspections++
                true
            }) { it },
        )
        assertEquals(0, inspections)
        assertEquals(0, reused.signals)
    }

    @Test
    fun `PID replacement during durable write cannot signal the replacement`() = runTest {
        val original = StopProcess(1)
        val reused = StopProcess(1, "2026-10-07T00:00:00Z")
        var current = original
        val persist = CompletableDeferred<Unit>()
        val waiting = async {
            stopper { current }.stop(Owner, { true }) {
                persist.await()
                it
            }
        }
        runCurrent()
        assertEquals(0, original.signals)
        current = reused
        persist.complete(Unit)
        assertTrue(waiting.await())
        assertEquals(0, original.signals)
        assertEquals(0, reused.signals)
    }

    @Test
    fun `new descendants are persisted before signals and root plus child exit are both required`() = runTest {
        val root = StopProcess(1)
        val child = StopProcess(2)
        root.observed = listOf(child)
        val persist = CompletableDeferred<Unit>()
        val stop = stopper { if (it == 1L) root else child }
        val waiting = async {
            stop.stop(Owner, { true }) {
                assertEquals(listOf(2L), it.observedChildren.map { identity -> identity.pid })
                persist.await()
                it
            }
        }
        runCurrent()
        assertEquals(0, root.signals)
        assertEquals(0, child.signals)
        persist.complete(Unit)
        runCurrent()
        assertEquals(1, root.signals)
        assertEquals(1, child.signals)
        root.exit()
        runCurrent()
        assertFalse(waiting.isCompleted)
        child.exit()
        assertTrue(waiting.await())
    }

    @Test
    fun `timeout and cancellation retain observed identities for a later retry`() = runTest {
        val root = StopProcess(1)
        val child = StopProcess(2)
        root.observed = listOf(child)
        var saved = Owner
        val stop = stopper { if (it == 1L) root else child }
        val first = async {
            stop.stop(saved, { true }) {
                saved = it
                it
            }
        }
        runCurrent()
        first.cancelAndJoin()
        root.exit()
        root.observed = emptyList()
        assertFalse(stop.stop(saved, { true }) { it })
        child.exit()
        assertTrue(stop.stop(saved, { true }) { it })
    }

    @Test
    fun `live descendant of unknown identity refuses the attempt until a retry can identify it`() = runTest {
        val root = StopProcess(1)
        val child = StopProcess(2).apply { startedAt = null }
        root.observed = listOf(child)
        var records = 0
        val stop = stopper { if (it == 1L) root else child }
        assertFalse(
            stop.stop(Owner, { true }) {
                records++
                it
            },
        )
        assertEquals(0, records)
        assertEquals(0, root.signals)
        child.startedAt = Instant.parse("2026-10-06T00:00:01Z")
        val waiting = async { stop.stop(Owner, { true }) { it } }
        runCurrent()
        assertEquals(1, root.signals)
        assertEquals(1, child.signals)
        root.exit()
        child.exit()
        assertTrue(waiting.await())
    }

    @Test
    fun `descendants exiting during inspection are gone rather than a changed parent`() = runTest {
        val root = StopProcess(1)
        val child = StopProcess(2).apply { onEnumerate = { exit() } }
        // Alive when listed, it exits while its start instant is read.
        val grandchild = StopProcess(3).apply {
            startedAt = null
            onIdentity = { exit() }
        }
        child.observed = listOf(grandchild)
        val owner = Owner.copy(observedChildren = listOf(PiProcessIdentity(2, START)))
        val processes = listOf(root, child, grandchild)
        var saved: PiExecutionOwner? = null
        val waiting = async {
            stopper { pid -> processes.first { it.pid() == pid } }.stop(owner, { true }) { discovered ->
                discovered.also { saved = it }
            }
        }
        runCurrent()
        assertEquals(owner, saved)
        assertEquals(1, root.signals)
        assertEquals(0, child.signals)
        assertEquals(0, grandchild.signals)
        root.exit()
        assertTrue(waiting.await())
    }

    @Test
    fun `exit proof needs every stored identity gone and never signals`() = runTest {
        val root = StopProcess(1)
        val owner = Owner.copy(observedChildren = listOf(PiProcessIdentity(2, START)))
        assertTrue(stopper { null }.hasExited(owner))
        assertTrue(stopper { StopProcess(it, "2026-10-07T00:00:00Z") }.hasExited(owner))
        assertFalse(stopper { if (it == 1L) root else null }.hasExited(owner))
        root.startedAt = null
        assertFalse(stopper { if (it == 1L) root else null }.hasExited(owner))
        root.exit()
        assertTrue(stopper { if (it == 1L) root else null }.hasExited(owner))
        assertEquals(0, root.signals)
    }

    @Test
    fun `failure persisting observed children cannot signal any process`() = runTest {
        val root = StopProcess(1)
        assertFalse(stopper { root }.stop(Owner, { true }) { error("Storage private details") })
        assertEquals(0, root.signals)
    }

    private fun TestScope.stopper(lookup: (Long) -> ProcessHandle?): PiStoredProcessStop {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        return PiStoredProcessStop(dispatchers, lookup, timeoutMillis = 100)
    }

    private companion object {
        const val START = "2026-10-06T00:00:00Z"
        val Owner = PiExecutionOwner("launch", PiProcessIdentity(1, START))
    }

    /** Signalling and exit are independent. No fake method accesses an OS PID. */
    private class StopProcess(private val id: Long, start: String = START) : ProcessHandle {
        var startedAt: Instant? = Instant.parse(start)
        var observed: List<ProcessHandle> = emptyList()
        var signals = 0
        var onEnumerate: () -> Unit = {}
        var onIdentity: () -> Unit = {}
        private val exited = CompletableFuture<ProcessHandle>()
        fun exit() {
            exited.complete(this)
        }
        override fun pid(): Long = id
        override fun parent(): Optional<ProcessHandle> = Optional.empty()
        override fun children(): Stream<ProcessHandle> = observed.stream()
        override fun descendants(): Stream<ProcessHandle> {
            onEnumerate()
            return observed.stream()
        }
        override fun info(): ProcessHandle.Info = object : ProcessHandle.Info {
            override fun startInstant(): Optional<Instant> {
                onIdentity()
                return Optional.ofNullable(startedAt)
            }
            override fun command(): Optional<String> = error("Unused")
            override fun commandLine(): Optional<String> = error("Unused")
            override fun arguments(): Optional<Array<String>> = error("Unused")
            override fun totalCpuDuration(): Optional<Duration> = error("Unused")
            override fun user(): Optional<String> = error("Unused")
        }
        override fun onExit(): CompletableFuture<ProcessHandle> = exited.thenApply { it }
        override fun supportsNormalTermination(): Boolean = true
        override fun isAlive(): Boolean = !exited.isDone
        override fun destroy(): Boolean {
            signals++
            return true
        }
        override fun destroyForcibly(): Boolean = destroy()
        override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
    }
}
