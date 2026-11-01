package io.aequicor.heartbeat.feature.autocontinue.impl.domain

import io.aequicor.heartbeat.feature.autocontinue.api.AutopilotContextSettings
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationAnswer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AutopilotContextSerializationTest {
    @Test
    fun `persisted source switches and false model confirmation keep their existing JSON keys`() {
        val json = Json { encodeDefaults = true }
        val stored = Json.parseToJsonElement(
            """{
                "history":false,"plan":false,"toolResults":true,"toolArguments":true,
                "checklist":true,"questionnaires":true,"backgroundResults":true,"historyMessages":40
            }""",
        )
        val decoded = json.decodeFromJsonElement(AutopilotContextSettings.serializer(), stored)
        assertEquals(
            AutopilotContextSettings(
                isHistoryIncluded = false,
                isPlanIncluded = false,
                isToolResultsIncluded = true,
                isToolArgumentsIncluded = true,
                isChecklistIncluded = true,
                isQuestionnairesIncluded = true,
                isBackgroundResultsIncluded = true,
                historyMessages = 40,
            ),
            decoded,
        )
        assertEquals(stored, json.encodeToJsonElement(AutopilotContextSettings.serializer(), decoded))
        val modelAnswer = Json.parseToJsonElement("""{"selected":[],"confirmed":false,"text":null}""")
        val answer = json.decodeFromJsonElement(ContinuationAnswer.serializer(), modelAnswer)
        assertEquals(false, answer.isConfirmed)
        assertEquals(modelAnswer, json.encodeToJsonElement(ContinuationAnswer.serializer(), answer))
    }
}
