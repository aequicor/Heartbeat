package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pi extension dialog (`select`, `confirm`, `input`, `editor`) that is not a tool approval, surfaced as a
 * [PermissionRequest] with structured input. [reply] builds the matching `extension_ui_response` field:
 * `value` for an answer, `confirmed` for a confirmation, `cancelled` when the user skipped.
 */
internal sealed interface PiDialog {
    val request: PermissionRequest

    fun reply(decision: PermissionDecision): Pair<String, JsonElement>

    /** Yes/no question; the chosen option is the answer. */
    data class Confirm(override val request: PermissionRequest) : PiDialog {
        override fun reply(decision: PermissionDecision): Pair<String, JsonElement> =
            "confirmed" to JsonPrimitive(decision.option == YesOption)
    }

    /** One of Pi's option strings; choice ids are their indexes. */
    data class Select(override val request: PermissionRequest, val values: List<String>) : PiDialog {
        override fun reply(decision: PermissionDecision): Pair<String, JsonElement> {
            val index = (decision.answer as? PermissionAnswer.Selected)?.ids?.singleOrNull()?.toIntOrNull()
            val value = index?.let(values::getOrNull)
            if (decision.option == AnswerOption && value != null) return "value" to JsonPrimitive(value)
            if (decision.option != SkipOption) log.w { "Pi select dialog cancelled: the answer is missing or invalid" }
            return CANCELLED
        }
    }

    /** Free text from `input` (single line) or `editor` (multiline). */
    data class Text(override val request: PermissionRequest) : PiDialog {
        override fun reply(decision: PermissionDecision): Pair<String, JsonElement> {
            val text = (decision.answer as? PermissionAnswer.Text)?.value
            if (decision.option == AnswerOption && text != null) return "value" to JsonPrimitive(text)
            if (decision.option != SkipOption) log.w { "Pi text dialog cancelled: the answer is missing" }
            return CANCELLED
        }
    }

    companion object {
        val YesOption = PermissionOptionId("yes")
        val NoOption = PermissionOptionId("no")
        val AnswerOption = PermissionOptionId("answer")
        val SkipOption = PermissionOptionId("skip")
        private val CANCELLED = "cancelled" to JsonPrimitive(true)
        private val log = Log.tag("PiDialog")

        /** Maps a non-approval dialog record; null when it is malformed and must be dismissed. */
        fun from(record: JsonObject, id: String, turn: TurnId): PiDialog? {
            val title = record.string("title")?.takeIf { it.isNotBlank() } ?: return null
            val requestId = PermissionRequestId(id)
            val answerActions = listOf(
                PermissionOption(AnswerOption, "Ответить"),
                PermissionOption(SkipOption, "Пропустить", isSkip = true),
            )
            return when (record.string("method")) {
                "confirm" -> Confirm(
                    PermissionRequest(
                        requestId,
                        turn,
                        title,
                        listOf(PermissionOption(YesOption, "Да"), PermissionOption(NoOption, "Нет")),
                        description = record.string("message"),
                    ),
                )

                "select" -> {
                    val values = (record["options"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    if (values.isEmpty()) return null
                    val choices = values.mapIndexed { index, value -> PermissionChoice(index.toString(), value) }
                    Select(
                        PermissionRequest(
                            requestId,
                            turn,
                            title,
                            answerActions,
                            input = PermissionInput.SingleChoice(choices),
                        ),
                        values,
                    )
                }

                "input", "editor" -> Text(
                    PermissionRequest(
                        requestId,
                        turn,
                        title,
                        answerActions,
                        input = PermissionInput.FreeText(
                            placeholder = record.string("placeholder"),
                            isMultiline = record.string("method") == "editor",
                        ),
                    ),
                )

                else -> null
            }
        }
    }
}
