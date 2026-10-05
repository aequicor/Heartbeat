package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.spi.SourceEvent
import io.aequicor.heartbeat.feature.scheduler.impl.data.NetworkEventSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkEventSourceTest {
    @Test
    fun `only transitions are published`() = runTest {
        val network = FakeNetwork(isConnected = true)
        val events = mutableListOf<SourceEvent>()
        backgroundScope.launch(
            start = CoroutineStart.UNDISPATCHED,
        ) { NetworkEventSource(network).events().toList(events) }
        runCurrent()
        network.state.value = false
        runCurrent()
        network.state.value = null
        runCurrent()
        network.state.value = true
        runCurrent()
        assertEquals(listOf(SourceEvent(EventKeys.NetworkLost), SourceEvent(EventKeys.NetworkAvailable)), events)
    }
}
