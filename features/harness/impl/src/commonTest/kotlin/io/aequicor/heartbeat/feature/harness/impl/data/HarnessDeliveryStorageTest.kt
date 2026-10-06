package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.KeyValueHarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryMarker
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class HarnessDeliveryStorageTest {
    private val clock = RunDeliveryClock()
    private val kv = RunDeliveryTestStore(clock)
    private fun repository() = KeyValueHarnessDeliveryStorage(kv, clock)
    private fun session(value: String = "private-session") = SessionRef(
        EngineId("pi"),
        SessionSourceId("source"),
        value,
    )
    private fun marker(value: String = "sample") = HarnessDeliveryMarker(HarnessId(value), HarnessName(value), 1)
    private val digest = "b".repeat(64)

    @Test
    fun `raw access failures hide messages while cancellation remains cancellation`() = runTest {
        kv.readFailureKey = "deliveries"
        val error = assertFailsWith<HarnessStorageUncertain> { repository().snapshot(session()) }
        assertFalse(error.toString().contains("private-store-message"))
        assertNull(error.cause)
        kv.readFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { repository().snapshot(session()) }
    }

    @Test
    fun `snapshot is not acceptance and exact feedback retry leaves the durable receipt unchanged`() = runTest {
        val session = session()
        val snapshot = repository().snapshot(session)
        assertNull(snapshot.activeSetSha)
        val writes = kv.writes
        assertEquals(snapshot, repository().snapshot(session))
        assertEquals(writes, kv.writes)
        assertTrue(repository().accepted(session, snapshot.generation, digest, listOf(marker()), emptySet()))
        val accepted = repository().snapshot(session)
        val acceptedWrites = kv.writes
        assertFalse(repository().accepted(session, snapshot.generation, digest, listOf(marker()), emptySet()))
        assertEquals(acceptedWrites, kv.writes)
        assertEquals(digest, accepted.activeSetSha)
        assertEquals(listOf(marker()), accepted.markers)
    }

    @Test
    fun `compaction fences late Accepted while retaining names for subsequent deletion notices`() = runTest {
        val session = session()
        val first = repository().snapshot(session)
        repository().accepted(session, first.generation, digest, listOf(marker()), emptySet())
        val before = repository().snapshot(session)
        repository().reset(session)
        assertFalse(repository().accepted(session, before.generation, digest, listOf(marker()), emptySet()))
        val reset = repository().snapshot(session)
        assertNull(reset.activeSetSha)
        assertEquals(listOf(marker()), reset.markers)
        assertNotEquals(before.generation, reset.generation)
    }

    @Test
    fun `deletion preserves disabled name and requires every pending notice before installing another hash`() =
        runTest {
            val session = session()
            val first = repository().snapshot(session)
            val original = listOf(marker("one"), marker("two"))
            repository().accepted(session, first.generation, digest, original, emptySet())
            val before = repository().snapshot(session)
            repository().removeHarness(HarnessId("one"))
            repository().removeHarness(HarnessId("two"))
            val deleted = repository().snapshot(session)
            assertNull(deleted.activeSetSha)
            assertTrue(deleted.markers.isEmpty())
            assertEquals(setOf(HarnessName("one"), HarnessName("two")), deleted.pendingDisabled)
            assertFalse(repository().accepted(session, before.generation, digest, original, emptySet()))
            assertFalse(
                repository().accepted(session, deleted.generation, digest, listOf(marker()), setOf(HarnessName("one"))),
            )
            assertEquals(deleted, repository().snapshot(session))
            assertTrue(
                repository().accepted(session, deleted.generation, digest, listOf(marker()), deleted.pendingDisabled),
            )
            assertTrue(repository().snapshot(session).pendingDisabled.isEmpty())
        }

    @Test
    fun `expiry rotates generation and cannot resurrect through late acceptance`() = runTest {
        val session = session()
        val old = repository().snapshot(session)
        assertEquals(clock.now() + 30.days, (kv.retention["deliveries"]?.expiry as Expiry.At).instant)
        clock.instant += 31.days
        val fresh = repository().snapshot(session)
        assertNotEquals(old.generation, fresh.generation)
        assertFalse(repository().accepted(session, old.generation, digest, listOf(marker()), emptySet()))
    }

    @Test
    fun `bounded session receipts evict oldest and reject more than eight accepted markers`() = runTest {
        val first = repository().snapshot(session("first"))
        repeat(256) { number ->
            clock.instant += 1.seconds
            repository().snapshot(session("session$number"))
        }
        assertNotEquals(first.generation, repository().snapshot(session("first")).generation)
        val current = repository().snapshot(session("first"))
        assertFailsWith<HarnessStorageConflict> {
            repository().accepted(
                session("first"),
                current.generation,
                digest,
                (0..8).map { marker("h$it") },
                emptySet(),
            )
        }
        assertEquals(current, repository().snapshot(session("first")))
    }

    @Test
    fun `corruption fails without exposing JSON and receipt formatting hides session and names`() = runTest {
        val snapshot = repository().snapshot(session())
        assertFalse(snapshot.toString().contains("private-session"))
        assertFalse(marker("private_name").toString().contains("private_name"))
        kv.values["deliveries"] = "private-source-corrupt-json"
        val error = assertFailsWith<HarnessStorageCorrupt> { repository().snapshot(session()) }
        assertFalse(error.toString().contains("private-source"))
        assertNull(error.cause)
    }
}
