package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PiSessionStartupTest {
    @Test
    fun `startup accepts thinking events before the native identity is known`() = runTest {
        var delivered = false
        val fixture = fixture(this, configure = { _, connection ->
            connection.onSetModel = {
                connection.event(record("""{"type":"thinking_level_changed","level":"medium"}"""))
                delivered = true
            }
        })
        assertTrue(delivered)
        assertEquals("native", fixture.session.ref.nativeId)
        fixture.runningTurn()
        fixture.connection.event(record("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
        fixture.connection.event(
            record(
                """{"type":"message_update","assistantMessageEvent":{
                    "type":"thinking_delta","contentIndex":0,"delta":"Checking the goal"}}
                """,
            ),
        )
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            fixture.session.features.resolve(SessionHistory),
        ).feature
        val answer = assertIs<SessionItem.Message>(history.page().items.last())
        assertEquals(listOf(ContentPart.Reasoning("Checking the goal")), answer.parts)
        fixture.session.shutdown()
    }
}
