package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * Serves native user-input batches without blocking the event loop. Pending forms share the session's decision
 * bridge; native resolution cancels the batch, and its turn-owned parent revokes it on interruption or completion.
 */
internal class CodexUserInput(
    private val scope: CoroutineScope,
    private val respond: suspend (JsonElement, JsonObject) -> Unit,
    private val ask: suspend (PermissionRequest) -> PermissionDecision?,
) {
    private val log = Log.tag("CodexUserInput")
    private val jobs = mutableMapOf<JsonElement, Job>()

    suspend fun request(message: JsonObject, turn: TurnId?, parent: Job?) {
        val id = message["id"] ?: protocolFailure()
        val questions = codexQuestions(message.obj("params"))
        if (turn == null || parent == null || questions == null) {
            log.w { "Codex user input refused: unavailable turn or unsupported questions" }
            respond(id, json("answers" to JsonObject(emptyMap())))
            return
        }
        if (id in jobs) return
        log.i { "Codex questionnaire requested count=${questions.size}" }
        val job = scope.launch(parent, start = CoroutineStart.LAZY) {
            respond(id, answerCodexQuestions(questions, turn, ask))
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
        job.start()
    }

    fun resolved(id: JsonElement) {
        jobs.remove(id)?.cancel()
    }
}

/** A native question keeps wire labels separate from local choice ids and presentation descriptions. */
internal data class CodexQuestion(
    val id: String,
    val title: String,
    val labels: List<String>,
    val description: String?,
    val hasOther: Boolean,
)

/** Rejects malformed batches and secret inputs, which the ordinary questionnaire cannot mask. */
internal fun codexQuestions(params: JsonObject): List<CodexQuestion>? {
    val questions = params["questions"] as? JsonArray ?: return null
    if (questions.isEmpty()) return null
    val parsed = questions.map { element ->
        val question = element as? JsonObject ?: return null
        if (question["isSecret"] == JsonPrimitive(true)) return null
        val id = question.text("id")?.takeIf(String::isNotBlank) ?: return null
        val title = question.text("question")?.takeIf(String::isNotBlank) ?: return null
        val options = question["options"] as? JsonArray
        val labels = options.orEmpty().map { option ->
            (option as? JsonObject)?.text("label")?.takeIf(String::isNotBlank) ?: return null
        }
        val descriptions = options.orEmpty().mapIndexedNotNull { index, option ->
            (option as? JsonObject)?.text("description")?.takeIf(String::isNotBlank)?.let {
                "${labels[index]}: $it"
            }
        }
        CodexQuestion(
            id,
            title,
            labels,
            descriptions.joinToString("\n").ifBlank { null },
            question["isOther"] == JsonPrimitive(true),
        )
    }
    return parsed.takeIf { it.map(CodexQuestion::id).distinct().size == it.size }
}

/**
 * Converts the native batch into the existing structured permission inputs. The Other choice opens a free-text
 * follow-up; no selected option or empty form is submitted without an explicit user decision.
 */
internal suspend fun answerCodexQuestions(
    questions: List<CodexQuestion>,
    turn: TurnId,
    ask: suspend (PermissionRequest) -> PermissionDecision?,
): JsonObject {
    val answers = mutableMapOf<String, JsonObject>()
    for (question in questions) {
        val decision = question.answer(turn, ask) ?: break
        val values = when (val answer = decision.answer) {
            is PermissionAnswer.Text -> listOf(answer.value)
            is PermissionAnswer.Selected -> answer.ids.map { question.labels[it.toInt()] }
            null -> emptyList()
        }
        answers[question.id] = json("answers" to JsonArray(values.map(::JsonPrimitive)))
    }
    return json("answers" to JsonObject(answers))
}

private suspend fun CodexQuestion.answer(
    turn: TurnId,
    ask: suspend (PermissionRequest) -> PermissionDecision?,
): PermissionDecision? {
    val choices = choices()
    val input = if (choices.isEmpty()) {
        PermissionInput.FreeText(isMultiline = true)
    } else {
        PermissionInput.SingleChoice(choices)
    }
    val decision = ask(request(turn, input))
    return if ((decision?.answer as? PermissionAnswer.Selected)?.ids == listOf(OTHER)) {
        ask(request(turn, PermissionInput.FreeText(isMultiline = true)))
    } else {
        decision
    }
}

private fun CodexQuestion.choices(): List<PermissionChoice> =
    labels.mapIndexed { index, label -> PermissionChoice(index.toString(), label) } +
        if (hasOther && labels.isNotEmpty()) listOf(PermissionChoice(OTHER, "Другой ответ")) else emptyList()

private fun CodexQuestion.request(turn: TurnId, input: PermissionInput) = PermissionRequest(
    PermissionRequestId(Uuid.random().toString()),
    turn,
    title,
    listOf(
        PermissionOption(PermissionOptionId("question.submit"), "Ответить"),
        PermissionOption(PermissionOptionId("question.skip"), "Пропустить", isSkip = true),
    ),
    description = description,
    input = input,
)

private const val OTHER = "other"
