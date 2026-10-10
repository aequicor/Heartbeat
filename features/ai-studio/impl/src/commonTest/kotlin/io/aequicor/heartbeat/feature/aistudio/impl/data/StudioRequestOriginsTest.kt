package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioRequestOriginsTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("source"), "target")
    private val request = RequestId("R")
    private val causes = setOf(RequestInitiator(session.copy(nativeId = "origin"), RequestId("source-R")))

    @Test
    fun `ordinary turns leave observers lazy and relays use exact target identity`() = runTest {
        StudioRequestOrigins(lazy { error("must stay lazy") }).record(session, request, emptySet())
        var recorded = 0
        val observers = StudioRequestOrigins(
            lazyOf(
                setOf(
                    ScheduledRequestOriginObserver {
                        assertEquals(session, it.session)
                        assertEquals(request, it.request)
                        assertEquals(causes, it.causes)
                        recorded++
                    },
                ),
            ),
        )
        observers.record(session, request, causes)
        assertEquals(1, recorded)
    }

    @Test
    fun `observer failure propagates and self cancellation cannot become permission`() = runTest {
        val failing = StudioRequestOrigins(lazyOf(setOf(ScheduledRequestOriginObserver { error("storage failed") })))
        assertFailsWith<IllegalStateException> { failing.record(session, request, causes) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waiting = StudioRequestOrigins(
            lazyOf(
                setOf(
                    ScheduledRequestOriginObserver {
                        entered.complete(Unit)
                        release.await()
                    },
                ),
            ),
        )
        var nativeCalls = 0
        val run = async {
            waiting.record(session, request, causes)
            nativeCalls++
        }
        entered.await()
        run.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { run.await() }
        assertEquals(0, nativeCalls)
    }
}
