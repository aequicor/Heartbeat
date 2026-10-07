package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.KeyValueHarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryMarker
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContent
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContextDelivery
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessContextDeliveryTest {
    private val clock = RunDeliveryClock()
    private val kv = RunDeliveryTestStore(clock)
    private val storage = KeyValueHarnessDeliveryStorage(kv, clock)
    private val context = SessionHookContext(
        SessionRef(EngineId("pi"), SessionSourceId("source"), "session"),
        null,
        RequestId("request"),
        TurnId("turn"),
        SessionOwner("owner"),
    )
    private val content = HarnessContent(listOf(harness))

    @Test
    fun `late uncertain acceptance after an accepted disable must revoke possible instructions again`() = runTest {
        val delivery = HarnessContextDelivery(storage, backgroundScope, ::digest)
        val old = assertNotNull(delivery.prepare(context, "native", content))
        val disabled = assertNotNull(delivery.prepare(context, "native", HarnessContent(emptyList())))
        disabled.receipt!!.accepted(context, "native")
        assertNull(delivery.prepare(context, "native", HarnessContent(emptyList())))
        old.receipt!!.accepted(context, "native")
        val repeated = assertNotNull(delivery.prepare(context, "native", HarnessContent(emptyList())))
        assertTrue(repeated.text.contains("Все харнессы отключены."))
        repeated.receipt!!.accepted(context, "native")
        assertNull(delivery.prepare(context, "native", HarnessContent(emptyList())))
    }

    @Test
    fun `lost receipt across restart still revokes possibly delivered instructions when toggled off`() = runTest {
        val old = HarnessContextDelivery(storage, backgroundScope, ::digest)
        assertNotNull(old.prepare(context, "old-process", content))
        // The native process may have accepted this block; the profile lost its receipt before writing it.
        val restoredStorage = KeyValueHarnessDeliveryStorage(kv, clock)
        assertTrue(restoredStorage.snapshot(context.session).isDeliveryPending)
        val restored = HarnessContextDelivery(restoredStorage, backgroundScope, ::digest)
        val omitted = assertNotNull(restored.prepare(context, "new-process", HarnessContent(emptyList())))
        omitted.receipt!!.discarded()
        assertTrue(restoredStorage.snapshot(context.session).isDeliveryPending)
        val disabled = assertNotNull(restored.prepare(context, "new-process", HarnessContent(emptyList())))
        assertTrue(disabled.text.contains("Все харнессы отключены."))
        disabled.receipt!!.accepted(context, "new-process")
        assertNull(restored.prepare(context, "new-process", HarnessContent(emptyList())))
        kotlin.test.assertFalse(restoredStorage.snapshot(context.session).isDeliveryPending)
    }

    @Test
    fun `acceptance is ordered before next preparation and duplicate feedback cannot invalidate it`() = runTest {
        val delivery = HarnessContextDelivery(storage, backgroundScope, ::digest)
        val block = assertNotNull(delivery.prepare(context, "native", content))
        assertNull(storage.snapshot(context.session).activeSetSha)
        block.receipt!!.accepted(context, "native")
        block.receipt!!.accepted(context, "native")
        assertNull(delivery.prepare(context, "native", content))
        assertNotNull(storage.snapshot(context.session).activeSetSha)
    }

    @Test
    fun `discarded foreign and unknown retention receipts never suppress delivery`() = runTest {
        val delivery = HarnessContextDelivery(storage, backgroundScope, ::digest)
        val discarded = assertNotNull(delivery.prepare(context, "native", content))
        discarded.receipt!!.discarded()
        discarded.receipt!!.accepted(context, "native")
        val foreign = assertNotNull(delivery.prepare(context, "native", content))
        foreign.receipt!!.accepted(context.copy(owner = SessionOwner("foreign")), "native")
        assertNotNull(delivery.prepare(context, "native", content))
        val unknown = assertNotNull(delivery.prepare(context, null, content))
        unknown.receipt!!.accepted(context, null)
        assertNotNull(delivery.prepare(context, null, content))
    }

    @Test
    fun `compaction before or after native acceptance always requests a fresh complete block`() = runTest {
        val delivery = HarnessContextDelivery(storage, backgroundScope, ::digest)
        val before = assertNotNull(delivery.prepare(context, "before", content))
        before.receipt!!.accepted(context, "after")
        val after = assertNotNull(delivery.prepare(context, "after", content))
        after.receipt!!.accepted(context, "after")
        assertNull(delivery.prepare(context, "after", content))
        assertNotNull(delivery.prepare(context, "later", content))
    }

    @Test
    fun `superseded acceptance cannot preserve a hash for another native block`() = runTest {
        val delivery = HarnessContextDelivery(storage, backgroundScope, ::digest)
        val first = assertNotNull(delivery.prepare(context, "native", content))
        val changed = HarnessContent(listOf(harness.copy(revision = harness.revision + 1)))
        val second = assertNotNull(delivery.prepare(context, "native", changed))
        second.receipt!!.accepted(context, "native")
        first.receipt!!.accepted(context, "native")
        assertNotNull(delivery.prepare(context, "native", changed))
    }

    @Test
    fun `toggle off delivers disabled notices and a recreated name still receives fresh content`() = runTest {
        val delivery = HarnessContextDelivery(storage, backgroundScope, ::digest)
        val first = assertNotNull(delivery.prepare(context, "native", content))
        first.receipt!!.accepted(context, "native")
        assertNull(delivery.prepare(context, "native", content))
        storage.removeHarness(harness.id)
        val replacement = HarnessContent(listOf(harness.copy(id = HarnessId("replacement"))))
        val replaced = assertNotNull(delivery.prepare(context, "native", replacement))
        assertTrue(replaced.text.contains("${harness.name.value} отключён"))
        replaced.receipt!!.accepted(context, "native")
        val disabled = assertNotNull(delivery.prepare(context, "native", HarnessContent(emptyList())))
        assertTrue(disabled.text.contains("${harness.name.value} отключён"))
        disabled.receipt!!.accepted(context, "native")
        assertNull(delivery.prepare(context, "native", HarnessContent(emptyList())))
        assertTrue(storage.snapshot(context.session).markers.isEmpty())
    }

    @Test
    fun `a failed acceptance write keeps memory dirty even when storage may have committed its hash`() = runTest {
        var shouldFail = true
        val uncertain = object : HarnessDeliveryStorage by storage {
            override suspend fun accepted(
                session: SessionRef,
                expectedGeneration: String,
                activeSetSha: String,
                markers: List<HarnessDeliveryMarker>,
                coveredDisabled: Set<HarnessName>,
            ): Boolean {
                val result = storage.accepted(session, expectedGeneration, activeSetSha, markers, coveredDisabled)
                if (shouldFail) {
                    shouldFail = false
                    throw HarnessStorageUncertain()
                }
                return result
            }
        }
        val delivery = HarnessContextDelivery(uncertain, backgroundScope, ::digest)
        val first = assertNotNull(delivery.prepare(context, "native", content))
        first.receipt!!.accepted(context, "native")
        val retry = assertNotNull(delivery.prepare(context, "native", content))
        retry.receipt!!.accepted(context, "native")
        assertNull(delivery.prepare(context, "native", content))
        assertEquals(digest(content.canonical("native", "")), storage.snapshot(context.session).activeSetSha)
    }
}

private fun digest(value: String): String = value.encodeUtf8().sha256().hex()
