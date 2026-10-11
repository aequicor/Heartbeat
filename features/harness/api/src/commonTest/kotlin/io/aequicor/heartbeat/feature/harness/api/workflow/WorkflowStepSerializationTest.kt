package io.aequicor.heartbeat.feature.harness.api.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowStepSerializationTest {
    @Test
    fun `completed JSON null and arbitrary memo values survive journal roundtrip`() {
        val values = listOf(
            JsonNull,
            JsonPrimitive("private-result"),
            JsonPrimitive(42),
            JsonPrimitive(true),
            JsonArray(listOf(JsonNull)),
            JsonObject(mapOf("value" to JsonNull)),
        )
        for (format in formats()) {
            for (value in values) {
                val step = WorkflowStep(StepKey("s0"), "a".repeat(64), phase = StepPhase.Completed, result = value)
                assertEquals(step, format.decodeFromString<WorkflowStep>(format.encodeToString(step)))
            }
        }
    }

    @Test
    fun `unfinished step remains without a result for omitted and explicit null fields`() {
        val step = WorkflowStep(StepKey("s0"), "a".repeat(64))
        for (format in formats()) {
            val restored = format.decodeFromString<WorkflowStep>(format.encodeToString(step))
            assertEquals(step, restored)
            assertNull(restored.result)
        }
    }

    private fun formats(): List<Json> = listOf(
        Json,
        Json { encodeDefaults = true },
        Json { explicitNulls = false },
        Json {
            encodeDefaults = true
            explicitNulls = false
        },
    )
}
