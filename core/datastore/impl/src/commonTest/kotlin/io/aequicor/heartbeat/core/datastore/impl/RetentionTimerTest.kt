package io.aequicor.heartbeat.core.datastore.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class RetentionTimerTest {

    @Test
    fun `a completed deadline flow is not collected again`() = runTest {
        val clock = RetentionClock(object : Clock {
            override fun now() = START
        }) { TimeZone.UTC }
        var subscriptions = 0
        val deadlines = flow<Long?> {
            subscriptions++
            if (subscriptions > 1) throw CancellationException("completed deadline flow was collected again")
            emit(null)
        }

        runRetentionTimer("permanent storage", clock, deadlines) { error("no deadline to purge") }

        assertEquals(1, subscriptions)
    }
}
