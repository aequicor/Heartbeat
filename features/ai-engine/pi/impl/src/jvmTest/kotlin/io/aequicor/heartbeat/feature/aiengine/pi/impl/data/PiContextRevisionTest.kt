package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextRevision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PiContextRevisionTest {
    @Test
    fun `compaction changes retention even with usage disabled and old processes cannot change it`() = runTest {
        val fixture = fixture(this, isUsageEnabled = false)
        val revision = assertIs<FeatureAccess.Available<SessionContextRevision>>(
            fixture.session.features.resolve(SessionContextRevision),
        ).feature
        val original = assertNotNull(revision.state.value)
        fixture.connection.event(Json.parseToJsonElement("""{"type":"auto_compaction_start"}""").jsonObject)
        assertNull(revision.state.value)
        fixture.connection.event(Json.parseToJsonElement("""{"type":"auto_compaction_end"}""").jsonObject)
        val compacted = assertNotNull(revision.state.value)
        assertNotEquals(original, compacted)
        val stale = fixture.connection
        stale.isOpen = false
        stale.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.session.synchronize()
        val replaced = assertNotNull(revision.state.value)
        assertNotEquals(compacted, replaced)
        stale.event(Json.parseToJsonElement("""{"type":"compaction_start"}""").jsonObject)
        assertEquals(replaced, revision.state.value)
        stale.event(Json.parseToJsonElement("""{"type":"compaction_end"}""").jsonObject)
        assertEquals(replaced, revision.state.value)
        fixture.session.shutdown()
    }
}
