package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.data.connectivitySamples
import io.aequicor.heartbeat.feature.browser.impl.data.networkRecoveries
import io.aequicor.heartbeat.feature.browser.impl.domain.networkRecoveriesWhileEnabled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class DesktopBrowserConnectivityTest {
    @Test
    fun `startup and unknown samples never fabricate recovery`() = runTest {
        assertEquals(emptyList(), flowOf(true, true, null, true).networkRecoveries().toList())
    }

    @Test
    fun `each real reconnect emits one retry signal`() = runTest {
        assertEquals(
            listOf(Unit, Unit),
            flowOf(true, false, false, null, true, true, false, true).networkRecoveries().toList(),
        )
    }

    @Test
    fun `any inspection failure is an unknown sample instead of an observer failure`() = runTest {
        val results = ArrayDeque(listOf<() -> Boolean>({ true }, { error("interfaces unavailable") }, { false }))
        val samples = connectivitySamples({ results.removeFirst()() }, interval = 1.milliseconds).take(3).toList()

        assertEquals(listOf(true, null, false), samples)
    }

    @Test
    fun `disabled error pages never start network inspection`() = runTest {
        val errorPages = MutableStateFlow(false)
        var starts = 0
        val restored = flow<Unit> {
            starts++
            emit(Unit)
        }
        val signals = mutableListOf<Unit>()
        val job = launch { errorPages.networkRecoveriesWhileEnabled { restored }.collect { signals += it } }
        advanceUntilIdle()
        assertEquals(0, starts)

        errorPages.value = true
        advanceUntilIdle()
        errorPages.value = false
        advanceUntilIdle()

        assertEquals(1, starts)
        assertEquals(listOf(Unit), signals)
        job.cancel()
    }
}
