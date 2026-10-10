package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HelperHandoffTest {
    @Test
    fun `handoff round trips exact host identity without rendering private context`() {
        val handoff = HelperHandoff(
            RequestInitiator(
                SessionRef(EngineId("engine"), SessionSourceId("source"), "private-session"),
                RequestId("private-request"),
            ),
            "harness",
            "private-context",
        )
        assertEquals(handoff, Json.decodeFromString<HelperHandoff>(Json.encodeToString(handoff)))
        assertFalse(handoff.toString().contains("private"))
        assertFalse(
            HelperPrompt(RequestId("request"), "private-prompt", handoff = handoff).toString().contains("private"),
        )
    }

    @Test
    fun `owner context is bounded and cannot omit its namespace`() {
        assertFailsWith<IllegalArgumentException> { HelperHandoff(ownerFeature = "bad.feature") }
        assertFailsWith<IllegalArgumentException> { HelperHandoff(ownerContext = "context") }
        assertFailsWith<IllegalArgumentException> {
            HelperHandoff(ownerFeature = "harness", ownerContext = "x".repeat(SchedulerLimits.MAX_OWNER_CONTEXT + 1))
        }
        assertEquals(HelperHandoff(), Json.decodeFromString<HelperHandoff>("{}"))
    }
}
