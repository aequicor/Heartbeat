package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HelperHostTest {
    private val session = SessionRef(EngineId("engine"), SessionSourceId("source"), "parent")
    private val request = RequestId("request")
    private val helper = HelperId("helper")
    private val host = object : ScheduledSessionHost {
        override val priority = 0
        override suspend fun owns(session: SessionRef) = true
        override suspend fun wake(request: WakeRequest, prompt: WakePrompt): Unit = error("Must not wake")
    }

    @Test
    fun `legacy hosts refuse helper operations without silently spawning or accepting cancellation`() = runTest {
        assertFalse(host.canHostHelper(session))
        assertFalse(host.canHostHelper(null))
        assertFalse(host.isHelper(session))
        assertNull(host.helperMetadata(helper))
        assertNull(host.helperMetadata(session))
        assertEquals(emptyList(), host.ownedHelpers(ActionId("owner")))
        assertNull(host.helperResult(helper, request))
        assertFailsWith<UnsupportedOperationException> {
            host.createHelper(
                HelperCreateRequest(
                    ActionId("owner"),
                    session,
                    null,
                    EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model")),
                    "Private title",
                    TrustLevel.Ask,
                ),
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            host.promptHelper(helper, HelperPrompt(request, "Private prompt"))
        }
        assertFailsWith<UnsupportedOperationException> { host.cancelHelper(helper, request) }
    }

    @Test
    fun `omitted target reaches the host with parent identity for durable inheritance`() {
        val input = HelperCreateRequest(ActionId("owner"), null, null, null, "Private title", TrustLevel.Ask)
        assertNull(input.target)
        val inherited = input.copy(parent = session)
        assertEquals(session, inherited.parent)
        assertNull(inherited.target)
    }

    @Test
    fun `helper prompt and result do not expose text in diagnostics`() {
        assertFalse(HelperPrompt(request, "Private prompt").toString().contains("Private prompt"))
        val result = HelperResult(request, HelperOutcome.Completed, "Private answer")
        assertFalse(result.toString().contains("Private answer"))
        assertFailsWith<IllegalArgumentException> {
            HelperResult(request, HelperOutcome.Completed, "x".repeat(SchedulerLimits.MAX_PAYLOAD + 1))
        }
    }
}
