package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.data.networkRecoveries
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
