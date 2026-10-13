package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.FakePanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelFeatures
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelStoreTest {
    private fun TestScope.store(host: FakePanelHost): Store<PanelState, PanelIntent, PanelAction> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = HeartbeatStoreFactory(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        return panelStore(factory, PanelLink(host))
    }

    @Test
    fun `the link status and agent control of the service worker are shown`() = runTest {
        val host = FakePanelHost()
        store(host).subscribeAndTest {
            host.deliver("""{"type":"status","status":"waiting_for_app","features":null}""")
            advanceUntilIdle()
            assertEquals(PanelNotice.WaitingForApp, states.value.notice)

            host.deliver("""{"type":"status","status":"ready","features":{"studio":true,"agentControl":true}}""")
            host.deliver("""{"type":"agent","isAllowed":true,"controlled":3}""")
            advanceUntilIdle()

            assertNull(states.value.notice)
            assertTrue(states.value.isAgentControlOffered)
            assertEquals(AgentControl(isAllowed = true, controlled = 3), states.value.agent)
        }
    }

    @Test
    fun `consent and revoking tabs go to the service worker`() = runTest {
        val host = FakePanelHost()
        store(host).subscribeAndTest {
            PanelIntent.SetAgentControl(isAllowed = true) resultsIn { advanceUntilIdle() }
            PanelIntent.RevokeTabs resultsIn { advanceUntilIdle() }

            assertEquals(
                listOf(
                    JsonObject(mapOf("type" to JsonPrimitive("consent"), "isAllowed" to JsonPrimitive(true))),
                    JsonObject(mapOf("type" to JsonPrimitive("revokeTabs"))),
                ),
                host.posted,
            )
        }
    }

    @Test
    fun `connecting is quiet, a ready app without studio and unknown statuses are problems`() {
        assertFalse(checkNotNull(PanelState(status = LinkStatus.Handshaking).notice).isProblem)
        assertEquals(PanelNotice.StudioOff, PanelState(status = LinkStatus.Ready).notice)
        assertNull(PanelState(status = LinkStatus.Ready, features = PanelFeatures(isStudio = true)).notice)
        assertEquals(LinkStatus.Disconnected, LinkStatus.fromWire("from_a_newer_extension"))
    }
}
