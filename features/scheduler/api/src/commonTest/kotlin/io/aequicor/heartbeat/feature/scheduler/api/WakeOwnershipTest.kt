package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class WakeOwnershipTest {
    @Test
    fun `action origin retains initiator while legacy action still decodes`() {
        val origin = EventOrigin.Action(ActionId("action"), RequestInitiator(request().session, RequestId("source")))
        val encoded = Json.encodeToString(EventOrigin.serializer(), origin)
        assertEquals(origin, Json.decodeFromString(EventOrigin.serializer(), encoded))
        val legacy = JsonObject(Json.parseToJsonElement(encoded).jsonObject - "initiator")
        assertEquals(
            EventOrigin.Action(ActionId("action")),
            Json.decodeFromJsonElement(EventOrigin.serializer(), legacy),
        )
        assertFalse(origin.toString().contains("source"))
    }

    @Test
    fun `legacy persisted wakes decode with hidden note and no owner context`() {
        val serializer = WakeRequest.serializer()
        val json = Json { encodeDefaults = true }
        val legacy = JsonObject(
            json.encodeToJsonElement(serializer, request()).jsonObject - setOf(
                "ownerContext",
                "isNoteVisible",
                "initiator",
            ),
        )
        val decoded = json.decodeFromJsonElement(serializer, legacy)
        assertNull(decoded.ownerContext)
        assertNull(decoded.initiator)
        assertFalse(decoded.isNoteVisible)
        assertEquals(request(), decoded)
    }

    @Test
    fun `owned visible wake round trips private context without exposing it in descriptions`() {
        val request = request().copy(
            origin = WakeOrigin.Feature("harness"),
            ownerFeature = "harness",
            ownerContext = "private admission metadata",
            isNoteVisible = true,
            initiator = RequestInitiator(request().session, RequestId("private-request")),
        )
        val wake = ScheduledWake(request, Instant.fromEpochMilliseconds(1))
        val encoded = Json.encodeToString(ScheduledWake.serializer(), wake)
        assertEquals(wake, Json.decodeFromString(ScheduledWake.serializer(), encoded))
        assertFalse(request.toString().contains("private admission metadata"))
        assertFalse(wake.toString().contains("private admission metadata"))
        assertFalse(request.toString().contains(request.note))
    }

    @Test
    fun `host context is bounded and requires an owner while visible notes also require feature origin`() {
        assertEquals(4_096, SchedulerLimits.MAX_OWNER_CONTEXT)
        val owned = request().copy(ownerFeature = "harness")
        assertEquals(4_096, owned.copy(ownerContext = "x".repeat(4_096)).ownerContext?.length)
        assertFailsWith<IllegalArgumentException> { owned.copy(ownerContext = "x".repeat(4_097)) }
        assertFailsWith<IllegalArgumentException> { request().copy(ownerContext = "private") }
        listOf("", "feature.name", "Feature", "feature name", "x".repeat(65)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { owned.copy(ownerFeature = invalid, ownerContext = "private") }
        }
        assertFailsWith<IllegalArgumentException> { owned.copy(isNoteVisible = true) }
        assertFailsWith<IllegalArgumentException> {
            request().copy(origin = WakeOrigin.Feature("harness"), isNoteVisible = true)
        }
        assertEquals("", owned.copy(ownerContext = "").ownerContext)
    }

    @Test
    fun `delivery request identity preserves existing derivation exactly`() {
        assertEquals(RequestId("wake_delivery-1"), WakeId("delivery-1").deliveryRequestId())
        assertEquals(RequestId("wake_" + "x".repeat(64)), WakeId("x".repeat(64)).deliveryRequestId())
    }

    @Test
    fun `feature event origin round trips bounded context and rejects invalid namespaces`() {
        val origin = EventOrigin.Feature("harness", "private ancestry")
        val encoded = Json.encodeToString(EventOrigin.serializer(), origin)
        assertEquals(origin, Json.decodeFromString(EventOrigin.serializer(), encoded))
        assertFalse(origin.toString().contains("harness"))
        assertFalse(origin.toString().contains("private ancestry"))
        assertEquals(4_096, EventOrigin.Feature("feature_owner-1", "x".repeat(4_096)).context.length)
        assertFailsWith<IllegalArgumentException> { EventOrigin.Feature("harness", "x".repeat(4_097)) }
        listOf("", "feature.name", "Feature", "feature name", "x".repeat(65)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { EventOrigin.Feature(invalid, "") }
        }
    }

    @Test
    fun `session event origin preserves request while legacy origin decodes without it`() {
        val session = request().session
        val legacy = EventOrigin.Session(session)
        val encodedLegacy = Json.encodeToString(EventOrigin.serializer(), legacy)
        assertFalse("request" in encodedLegacy)
        assertEquals(legacy, Json.decodeFromString(EventOrigin.serializer(), encodedLegacy))
        val current = EventOrigin.Session(session, RequestId("private_request"))
        val encoded = Json.encodeToString(EventOrigin.serializer(), current)
        assertEquals(current, Json.decodeFromString(EventOrigin.serializer(), encoded))
        assertFalse("private_request" in current.toString())
    }

    @Test
    fun `host turn provenance round trips exact identity without logging it`() {
        val origin = EventOrigin.HostTurn(request().session, RequestId("private_request"))
        val encoded = Json.encodeToString(EventOrigin.serializer(), origin)
        assertEquals(origin, Json.decodeFromString(EventOrigin.serializer(), encoded))
        assertFalse("private_request" in origin.toString())
        assertFalse(origin.session.nativeId in origin.toString())
    }

    private fun request() = WakeRequest(
        WakeId("wake"),
        SessionRef(EngineId("engine"), SessionSourceId("source"), "session"),
        null,
        WakeCondition(deadline = Instant.fromEpochMilliseconds(1)),
        "private note",
        WakeOrigin.Agent(TurnId("turn")),
    )
}
