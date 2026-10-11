package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessTestStores
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.RequestOriginAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HarnessRequestOriginObserverTest {
    private val storage = MemoryHarnessRequestAncestry()
    private val observer = HarnessRequestOriginObserver(lazyOf(storage))
    private val first = RequestInitiator(dispatchSession, RequestId("first"))
    private val retry = first.copy(request = RequestId("retry"))
    private val attempt = RequestOriginAttempt(
        dispatchSession.copy(nativeId = "target"),
        RequestId("R"),
        setOf(first, retry),
    )
    private val harness = HarnessId("owner")

    @Test
    fun `all exact causes and prior target restrictions survive repeated relays without a runtime`() = runTest {
        storage.restrict(first.session, first.request, HarnessCallOrigin(sendChain = mapOf(harness to 1)))
        storage.restrict(retry.session, retry.request, HarnessCallOrigin(true, mapOf(harness to 2)))
        storage.restrict(attempt.session, attempt.request, HarnessCallOrigin(sendChain = mapOf(harness to 3)))
        observer.record(attempt)
        observer.record(attempt.copy(causes = emptySet()))
        assertEquals(HarnessCallOrigin(true, mapOf(harness to 3)), storage.lookup(attempt.session, attempt.request))
    }

    @Test
    fun `profile that never recorded ancestry relays without opening the database`() = runTest {
        val stores = HarnessTestStores()
        val stored = StoredHarnessRequestAncestry(stores)
        // HarnessTestStores refuses every database open, so a lookup that reached Room would throw here.
        HarnessRequestOriginObserver(lazyOf(stored)).record(attempt)
        assertNull(stored.lookup(attempt.session, attempt.request))
        stores.keyValue(HarnessAncestryMarkerSpec).set(HarnessAncestryMarkerKey, "1")
        assertFailsWith<IllegalStateException> { stored.lookup(attempt.session, attempt.request) }
    }

    @Test
    fun `relay waits for durable ancestry and propagates storage errors`() = runTest {
        storage.restrict(first.session, first.request, HarnessCallOrigin(true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.beforeRestrict = {
            entered.complete(Unit)
            release.await()
        }
        val recorded = async { observer.record(attempt) }
        entered.await()
        assertFalse(recorded.isCompleted)
        release.complete(Unit)
        recorded.await()
        storage.failure = IllegalStateException("unavailable")
        assertFailsWith<IllegalStateException> { observer.record(attempt) }
    }
}
