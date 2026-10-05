package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioChatResolver
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Choice
import io.aequicor.heartbeat.feature.questionnaire.api.LIVE_QUESTION_ID_PREFIX
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * The hosted tool behind the questionnaire (toggle `questionnaire.enabled`): the agent asks the user questions
 * that block its work and waits for the answers inside the tool call. Questions become cards of the asking chat
 * (`source` is its studio id), which the session pane already shows. The questions carry the
 * [LIVE_QUESTION_ID_PREFIX]: the tool collects answers itself, so the journal does not persist them and the
 * question bridge does not follow them up. An ended turn cancels the waiting call and withdraws the
 * questions whose withdrawal has not completed. Each invocation owns a distinct id namespace, so concurrent
 * batches (including retries with the same key) cannot replace or withdraw one another's cards.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class AskUserAgentTools(
    private val registry: MachineRegistry,
    private val chats: Lazy<StudioChatResolver>,
    private val toggles: FeatureToggles,
) : AgentToolContribution {
    private val log = Log.tag("AskUserTools")

    override val isDetachedSupported: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(QuestionnaireEnabled)) listOf(SPEC) else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (toggles.get(QuestionnaireEnabled)) {
        "questionnaire_ask asks the user questions that block your work: clarifications before starting, " +
            "open decisions, approvals. Put all blocking questions into one call instead of an 'open " +
            "questions' section in the reply text; the call returns when the user answers them and its " +
            "result holds the answers, skipped ones carry none. Ask only what you cannot decide yourself."
    } else {
        ""
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(QuestionnaireEnabled)) return AgentToolResult("The questionnaire is disabled", true)
        return try {
            ask(context, arguments)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Ask-user specification rejected" }
            AgentToolResult("Invalid questionnaire_ask specification", true)
        }
    }

    private suspend fun ask(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        // Resolved lazily: the repository that owns the mapping transitively needs the hosted tools dispatcher.
        val sessionId = chats.value.sessionIdOf(context.session)
            ?: return AgentToolResult("Questions are available only in chats hosted by the studio", true)
        val questions = specification(arguments)
        val owner = Uuid.random().toString()
        val asked = questions.associateWith { it.questionnaire(sessionId, owner) }
        val answers = HashMap<String, Answer>()
        val withdrawn = mutableSetOf<QuestionnaireId>()
        try {
            // The queue runs from the profile start while the toggle is on; wait for it before asking.
            val machine = registry.observe(QuestionnaireMachineKey).filterNotNull().first()
            val isAccepted = coroutineScope {
                // Subscribe directly before publishing: outputs are hot and have no replay.
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitAnswers(machine.outputs, asked, answers, withdrawn)
                }
                try {
                    asked.values.forEach { questionnaire ->
                        val result = registry.send(
                            QuestionnaireMachineKey,
                            QuestionnaireIntent.Public.Ask(questionnaire),
                        )
                        if (result != SendResult.Accepted) return@coroutineScope false
                    }
                    log.i { "Asked the user count=${asked.size}" }
                    collector.join()
                    true
                } finally {
                    collector.cancel()
                }
            }
            if (!isAccepted) {
                return AgentToolResult("The question queue refused the questions; retry with the same key", true)
            }
            log.i { "User answered count=${answers.size}/${asked.size}" }
        } finally {
            // A cancelled call (the turn ended) leaves no unanswered question behind.
            withContext(NonCancellable) {
                asked.values.forEach { questionnaire ->
                    if (questionnaire.id !in withdrawn) withdraw(questionnaire.id)
                }
            }
        }
        return AgentToolResult(Json.encodeToString(AskAnswers(answers.mapValues { (_, answer) -> answer.payload() })))
    }

    /** Waits until every question of the batch is answered; an answer is withdrawn as soon as it arrives. */
    private suspend fun awaitAnswers(
        outputs: Flow<QuestionnaireOutput>,
        asked: Map<PreparedQuestion, Questionnaire>,
        answers: MutableMap<String, Answer>,
        withdrawn: MutableSet<QuestionnaireId>,
    ) {
        outputs
            .filterIsInstance<QuestionnaireOutput.Answered>()
            .filter { output -> asked.values.any { it.id == output.questionnaire.id } }
            .takeWhile { output ->
                val question = asked.entries.first { it.value.id == output.questionnaire.id }.key
                answers[question.id] = output.answer
                if (withdraw(output.questionnaire.id)) withdrawn += output.questionnaire.id
                answers.size < asked.size
            }
            .collect()
    }

    private suspend fun withdraw(id: QuestionnaireId): Boolean {
        val result = registry.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Withdraw(id))
        if (result != SendResult.Accepted) log.w { "Question was not withdrawn: $result" }
        return result == SendResult.Accepted
    }
}

/** One validated tool question, ready to become a [Questionnaire] of [AskUserAgentTools]' chat. */
private data class PreparedQuestion(val args: QuestionArgs) {
    val id: String get() = args.id

    fun questionnaire(sessionId: String, owner: String): Questionnaire = Questionnaire(
        id = QuestionnaireId("$LIVE_QUESTION_ID_PREFIX$sessionId/$owner/${args.batchKey}/${args.id}"),
        source = sessionId,
        title = args.title,
        question = question(),
        description = args.description?.takeIf(String::isNotBlank),
        isSkippable = args.isOptional,
    )

    private fun question(): Question = when (args.kind) {
        QuestionKind.Confirm -> Question.Confirm(label(args.yes, "yes"), label(args.no, "no"))

        QuestionKind.SingleChoice -> Question.SingleChoice(choices())

        QuestionKind.MultiChoice -> {
            val choices = choices()
            val min = args.min ?: 0
            val max = args.max ?: choices.size
            require(min in 0..max && max <= choices.size) { "MultiChoice bounds do not fit its choices" }
            Question.MultiChoice(choices, min, max)
        }

        QuestionKind.FreeText -> Question.FreeText(args.placeholder, args.isMultiline)
    }

    private fun label(value: String?, name: String): String =
        requireNotNull(value?.takeIf(String::isNotBlank)) { "Confirm needs a $name label" }

    private fun choices(): List<Choice> {
        require(args.choices.isNotEmpty()) { "${args.kind.name} needs choices" }
        require(args.choices.size <= MAX_CHOICES) { "Too many choices" }
        require(args.choices.map { it.id }.distinct().size == args.choices.size) { "Choice ids must be distinct" }
        return args.choices.map { choice ->
            require(choice.id.isNotBlank()) { "A choice needs an id" }
            require(choice.title.isNotBlank()) { "Choice ${choice.id} needs a title" }
            Choice(choice.id, choice.title)
        }
    }
}

private fun specification(arguments: JsonObject): List<PreparedQuestion> {
    val batch = Json.decodeFromJsonElement<AskArgs>(arguments)
    require(batch.key.isNotBlank() && batch.key.length <= MAX_KEY) { "The batch needs a stable key" }
    require(batch.questions.isNotEmpty() && batch.questions.size <= MAX_QUESTIONS) {
        "A batch carries 1..$MAX_QUESTIONS questions"
    }
    require(batch.questions.map { it.id }.distinct().size == batch.questions.size) {
        "Question ids must be distinct"
    }
    return batch.questions.map { args ->
        require(args.id.isNotBlank() && args.id.length <= MAX_KEY) { "A question needs a stable id" }
        require(args.title.isNotBlank() && args.title.length <= MAX_TITLE) { "A question needs a title" }
        PreparedQuestion(args.copy(batchKey = batch.key))
    }
}

private fun Answer.payload(): AnswerPayload = when (this) {
    is Answer.Confirmed -> AnswerPayload(isConfirmed = isConfirmed)
    is Answer.Selected -> AnswerPayload(selected = ids)
    is Answer.Text -> AnswerPayload(text = value)
    Answer.Skipped -> AnswerPayload(isSkipped = true)
}

@Serializable
private data class AskArgs(val key: String, val questions: List<QuestionArgs>)

@Serializable
private data class QuestionArgs(
    val id: String,
    val title: String,
    val kind: QuestionKind,
    val description: String? = null,
    val isOptional: Boolean = false,
    val yes: String? = null,
    val no: String? = null,
    val choices: List<ChoiceArgs> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val placeholder: String? = null,
    val isMultiline: Boolean = false,
    /** Carried from the batch for identifier building; never serialized back. */
    val batchKey: String = "",
)

@Serializable
private data class ChoiceArgs(val id: String, val title: String)

@Serializable
private enum class QuestionKind { Confirm, SingleChoice, MultiChoice, FreeText }

@Serializable
private data class AskAnswers(val answers: Map<String, AnswerPayload> = emptyMap())

@Serializable
private data class AnswerPayload(
    val isConfirmed: Boolean? = null,
    val selected: List<String> = emptyList(),
    val text: String? = null,
    val isSkipped: Boolean = false,
)

private val SPEC = AgentToolSpec(
    TOOL,
    "Ask the user questions that block your work in a questionnaire card and wait for the answers",
    Json.parseToJsonElement(SCHEMA).jsonObject,
)

private const val TOOL = "questionnaire_ask"
private const val MAX_KEY = 128
private const val MAX_TITLE = 2000
private const val MAX_QUESTIONS = 8
private const val MAX_CHOICES = 64

private const val SCHEMA = """
{
  "type": "object",
  "required": [
    "key",
    "questions"
  ],
  "additionalProperties": false,
  "properties": {
    "key": {
      "type": "string",
      "description": "Stable batch key within this session; reuse it on retries"
    },
    "questions": {
      "type": "array",
      "minItems": 1,
      "maxItems": 8,
      "items": {
        "type": "object",
        "required": [
          "id",
          "title",
          "kind"
        ],
        "additionalProperties": false,
        "properties": {
          "id": {
            "type": "string",
            "description": "Stable question id within the batch"
          },
          "title": {
            "type": "string"
          },
          "kind": {
            "type": "string",
            "description": "Confirm asks yes/no, SingleChoice picks one option, MultiChoice picks several, FreeText takes a free-form answer",
            "enum": [
              "Confirm",
              "SingleChoice",
              "MultiChoice",
              "FreeText"
            ]
          },
          "description": {
            "type": "string"
          },
          "isOptional": {
            "type": "boolean",
            "default": false,
            "description": "The user may skip the question; a skipped one carries no answer"
          },
          "yes": {
            "type": "string",
            "description": "Confirm: the yes label"
          },
          "no": {
            "type": "string",
            "description": "Confirm: the no label"
          },
          "choices": {
            "type": "array",
            "items": {
              "type": "object",
              "required": [
                "id",
                "title"
              ],
              "additionalProperties": false,
              "properties": {
                "id": {
                  "type": "string"
                },
                "title": {
                  "type": "string"
                }
              }
            }
          },
          "min": {
            "type": "integer",
            "minimum": 0,
            "description": "MultiChoice: the fewest selections"
          },
          "max": {
            "type": "integer",
            "minimum": 0,
            "description": "MultiChoice: the most selections"
          },
          "placeholder": {
            "type": "string",
            "description": "FreeText: the empty-field hint"
          },
          "isMultiline": {
            "type": "boolean",
            "default": false,
            "description": "FreeText: ask for a larger editor"
          }
        }
      }
    }
  }
}
"""
