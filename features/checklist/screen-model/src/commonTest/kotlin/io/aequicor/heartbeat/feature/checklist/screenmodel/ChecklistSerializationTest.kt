package io.aequicor.heartbeat.feature.checklist.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChecklistSerializationTest {
    private val json = Json { classDiscriminator = "@type" }

    @Test
    fun `screen drafts and every input kind survive the remote wire`() {
        for (phase in ChecklistPhaseUi.entries) {
            val state = ChecklistScreenState(
                title = "Список",
                fields = persistentListOf(
                    ChecklistFieldUi(
                        "f", "Ответ", true, true, true, 0, 2,
                        persistentListOf(ChecklistChoiceUi("a", "Первый")), persistentSetOf("a"), "Я🚀",
                    ),
                ),
                phase = phase,
                isAutomatic = true,
                isCompletionAllowed = true,
                isSaving = true,
                hasFailed = true,
                isDeliveryFailed = true,
                isDeliveryPending = true,
            )
            assertEquals(state, json.decodeFromString<ChecklistScreenState>(json.encodeToString(state)))
        }
        val intents = listOf(
            ChecklistScreenIntent.Choice("f", "a"),
            ChecklistScreenIntent.Text("f", "Я🚀"),
            ChecklistScreenIntent.Complete,
            ChecklistScreenIntent.Retry,
            ChecklistScreenIntent.RetryDelivery,
        )
        for (intent in intents) {
            val encoded = json.encodeToString(ChecklistScreenIntent.serializer(), intent)
            assertEquals(intent, json.decodeFromString(ChecklistScreenIntent.serializer(), encoded))
        }
    }
}
