package io.aequicor.heartbeat.feature.feedback.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class FeedbackSerializationTest {
    @Test
    fun `native anchors routes and nullable defaults survive storage roundtrip`() {
        val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))
        val records = listOf(
            record("model", FeedbackChange.Model(target, target.copy(model = ModelId("next")))).copy(
                anchor = FeedbackAnchor(
                    SessionRef(target.engine, SessionSourceId("store"), "native"),
                    ItemId("item"),
                    TurnId("turn"),
                ),
                outcome = FeedbackOutcome.Applied(SessionConfiguration(ModelId("next"), "high", TrustLevel.Ask)),
            ),
            record("effort", FeedbackChange.Effort("high", null)),
            record("trust", FeedbackChange.Trust(null, TrustLevel.Full)),
        )
        val serializer = ListSerializer(FeedbackRecord.serializer())
        assertEquals(records, Json.decodeFromString(serializer, Json.encodeToString(serializer, records)))
    }

    @Test
    fun `operation identities reject paths and revisions reject negatives`() {
        assertFailsWith<IllegalArgumentException> { record("native/path", FeedbackChange.Effort(null, "high")) }
        assertFailsWith<IllegalArgumentException> {
            record("ok", FeedbackChange.Effort(null, "high")).copy(revision = -1)
        }
        assertFailsWith<IllegalArgumentException> { FeedbackChange.Effort("", null) }
    }

    private fun record(id: String, change: FeedbackChange) = FeedbackRecord(
        id = id,
        source = "chat",
        revision = 0,
        createdAt = Instant.parse("2026-09-30T10:00:00Z"),
        change = change,
        outcome = FeedbackOutcome.Pending,
    )
}
