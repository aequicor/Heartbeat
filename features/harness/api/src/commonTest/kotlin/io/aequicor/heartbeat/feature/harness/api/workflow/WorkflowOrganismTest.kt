package io.aequicor.heartbeat.feature.harness.api.workflow

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkflowOrganismTest {
    @Test
    fun `outcomes keep a stable journal shape`() {
        val shapes = mapOf(
            OrganismOutcome.Answered("done") to """{"type":"answered","text":"done"}""",
            OrganismOutcome.Refused(OrganismRefusal.Unavailable) to """{"type":"refused","reason":"Unavailable"}""",
            OrganismOutcome.Ended(OrganismEnd.NoModel) to """{"type":"ended","reason":"NoModel"}""",
        )
        for ((outcome, json) in shapes) {
            assertEquals(json, Json.encodeToString(OrganismOutcome.serializer(), outcome))
            assertEquals(outcome, Json.decodeFromString(OrganismOutcome.serializer(), json))
        }
    }

    @Test
    fun `answers and options never reach diagnostic strings`() {
        assertFalse(OrganismOutcome.Answered("private-answer").toString().contains("private-answer"))
        assertFalse(OrganismOptions("private-title").toString().contains("private-title"))
    }
}
