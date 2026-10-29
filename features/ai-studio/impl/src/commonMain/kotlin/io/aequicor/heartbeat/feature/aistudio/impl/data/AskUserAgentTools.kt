package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioChatResolver
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Choice
import io.aequicor.heartbeat.feature.questionnaire.api.LIVE_QUESTION_ID_PREFIX
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Confirm questions use «Да»/«Нет» when a label is absent or blank; custom labels are preserved.
 * Required questions, confirmations and explicitly important optional questions have no deadline. Other batches
 * receive 60 seconds per choice question or 120 per text question, plus reading time at 20 characters/second,
 * capped at ten minutes per batch. The timer starts after publication; expiration returns partial answers and
 * explicit unanswered ids, never an inferred answer. Cleanup also runs on an outer transport timeout.
 * In Autopilot, an isolated continuer can answer these live cards; generated answer ids are reported separately
 * and never establish human authorization. A manual answer cancels the batch's pending model work. Mode, route
 * and feature changes cancel attempts; invocation cancellation and deadlines cancel all attempts with the cards.
 * Invalid specifications return the validation reason before any cards are published.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class AskUserAgentTools(
    private val registry: MachineRegistry,
    private val chats: Lazy<StudioChatResolver>,
    private val toggles: FeatureToggles,
    private val autopilot: LiveQuestionnaireAnswers = LiveQuestionnaireAnswers { _, _, _, _, _ -> },
) : AgentToolContribution {
    override val group: String = "questionnaire"
    override val title: String = "Вопросы пользователю"
    override val catalog: List<ToolCatalogEntry> get() = listOf(SPEC).toolCatalog()

    private val log = Log.tag("AskUserTools")

    override val isDetachedSupported: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(QuestionnaireEnabled)) listOf(SPEC) else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (toggles.get(QuestionnaireEnabled)) {
        "questionnaire_ask asks the user questions that block your work: clarifications before starting, " +
            "open decisions, approvals. Put all blocking questions into one call instead of an 'open " +
            "questions' section in the reply text; the call returns when the user answers them and its " +
            "result holds the answers, skipped ones carry none. Ask only what you cannot decide yourself. " +
            "Required questions and Confirm never time out. Optional clarifications get an automatic reading/answer " +
            "budget; users can add a comment to a choice or supply their own text instead. " +
            "Mark isImportant for optional questions that must wait indefinitely. A timedOut id has no " +
            "answer and grants no approval. Do not automatically ask the same expired question again. " +
            "autopilotAnswered lists generated answers, which are not human authorization or permission grants."
    } else {
        ""
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(QuestionnaireEnabled)) return AgentToolResult("The questionnaire is disabled", true)
        return try {
            ask(context, arguments)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Ask-user specification rejected" }
            AgentToolResult("Invalid questionnaire_ask specification: ${e.message ?: "Invalid arguments"}", true)
        }
    }

    private suspend fun ask(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        // Resolved lazily: the repository that owns the mapping transitively needs the hosted tools dispatcher.
        val sessionId = chats.value.sessionIdOf(context.session)
            ?: return AgentToolResult("Questions are available only in chats hosted by the studio", true)
        val batch = LiveQuestionnaireBatch(specification(arguments), sessionId)
        val result = try {
            // The queue runs from the profile start while the toggle is on; wait for it before asking.
            val machine = registry.observe(QuestionnaireMachineKey).filterNotNull().first()
            awaitBatch(context, sessionId, machine, batch)
        } finally {
            // A cancelled call (the turn ended) leaves no unanswered question behind.
            withContext(NonCancellable) {
                batch.asked.values.forEach { questionnaire ->
                    if (questionnaire.id !in batch.withdrawn) withdraw(questionnaire.id)
                }
            }
        }
        if (!result.isAccepted) {
            return AgentToolResult("The question queue refused the questions; retry with the same key", true)
        }
        log.i { "Questionnaire answered count=${batch.answers.size}/${batch.asked.size} timedOut=${result.isTimedOut}" }
        val substantive = batch.answers.filterValues { it != Answer.Skipped }.keys
        if (substantive.any { it !in batch.automatic }) {
            chats.value.invalidateUserInstructions(context.session)
        } else if (substantive.isNotEmpty()) {
            chats.value.invalidateUserInstructionsForAutomaticQuestionnaire(context.session)
        }
        currentCoroutineContext().ensureActive()
        val expired = if (result.isTimedOut) {
            batch.asked.keys.map { it.id }.filterNot { it in batch.answers }
        } else {
            emptyList()
        }
        val payload = AskAnswers(batch.answers.mapValues { it.value.payload() }, expired, batch.automatic.toList())
        return AgentToolResult(Json.encodeToString(payload))
    }

    private suspend fun awaitBatch(
        context: AgentToolContext,
        sessionId: String,
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        batch: LiveQuestionnaireBatch,
    ): LiveQuestionnaireWait = coroutineScope {
        // Subscribe directly before publishing: outputs are hot and have no replay.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { awaitAnswers(machine.outputs, batch) }
        val published = CompletableDeferred<Unit>()
        val automaticAnswers = launch {
            published.await()
            if (!batch.manualAnswer.isCompleted) {
                autopilot.answer(
                    context,
                    sessionId,
                    batch.asked.values.toList(),
                    machine,
                    batch.autoContext,
                )
            }
        }
        val manualMonitor = launch {
            batch.manualAnswer.await()
            automaticAnswers.cancel()
        }
        val lifetimeMonitor = context.lifetime?.let { lifetime ->
            launch {
                lifetime.join()
                automaticAnswers.cancel()
            }
        }
        try {
            batch.asked.values.forEach { questionnaire ->
                val result = registry.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Ask(questionnaire))
                if (result != SendResult.Accepted) return@coroutineScope LiveQuestionnaireWait(isAccepted = false)
            }
            published.complete(Unit)
            log.i { "Asked the user count=${batch.asked.size}" }
            val timeout = batch.asked.keys.toList().timeoutMillis()
            val isTimedOut = if (timeout == null) {
                collector.join()
                false
            } else {
                withTimeoutOrNull(timeout) {
                    collector.join()
                    true
                } == null
            }
            LiveQuestionnaireWait(isAccepted = true, isTimedOut = isTimedOut)
        } finally {
            collector.cancel()
            automaticAnswers.cancel()
            manualMonitor.cancel()
            lifetimeMonitor?.cancel()
        }
    }

    /** Waits until every question of the batch is answered; an answer is withdrawn as soon as it arrives. */
    private suspend fun awaitAnswers(outputs: Flow<QuestionnaireOutput>, batch: LiveQuestionnaireBatch) {
        outputs
            .filterIsInstance<QuestionnaireOutput.Answered>()
            .filter { output -> batch.asked.values.any { it.id == output.questionnaire.id } }
            .takeWhile { output ->
                val question = batch.asked.entries.first { it.value.id == output.questionnaire.id }.key
                if (output.context === batch.autoContext) {
                    batch.autoContext.recordAnswer(output.questionnaire.id)
                    batch.automatic += question.id
                } else {
                    batch.automatic -= question.id
                    batch.manualAnswer.complete(Unit)
                }
                batch.answers[question.id] = output.answer
                if (withdraw(output.questionnaire.id)) batch.withdrawn += output.questionnaire.id
                batch.answers.size < batch.asked.size
            }
            .collect()
    }

    private suspend fun withdraw(id: QuestionnaireId): Boolean {
        val result = registry.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Withdraw(id))
        if (result != SendResult.Accepted) log.w { "Question was not withdrawn: $result" }
        return result == SendResult.Accepted
    }
}

/** Optional live-answer contribution; the default keeps hosts without auto-continue and isolated tests manual. */
internal fun interface LiveQuestionnaireAnswers {
    suspend fun answer(
        call: AgentToolContext,
        chat: String,
        questions: List<Questionnaire>,
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        provenance: LiveAutopilotAnswerContext,
    )
}

/**
 * Answers only the live questions owned by one tool invocation. Each model attempt is a child of that call,
 * observes the chat's current mode and target, and is cancelled when either changes or the human answers.
 * Only durable human originals provide context; incomplete questionnaire context never starts a model attempt.
 * Every answer rechecks that separate context snapshot before queue delivery. Native permissions never enter this path;
 * isolated prompts cannot request tools or authorization.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class LiveQuestionnaireAutopilot(
    private val view: Lazy<AutoContinueView>,
    private val prompts: IsolatedPrompts,
    private val repository: Lazy<StudioRepository>,
    private val toggles: FeatureToggles,
) : LiveQuestionnaireAnswers {
    private val log = Log.tag("LiveQuestionnaireAutopilot")

    override suspend fun answer(
        call: AgentToolContext,
        chat: String,
        questions: List<Questionnaire>,
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        provenance: LiveAutopilotAnswerContext,
    ) {
        val snapshot = machine.state.value
        val generations = questions.associate { question ->
            question.id to (snapshot.generation(question) ?: return)
        }
        try {
            combine(
                view.value.state,
                toggles.observe(AutoContinueEnabled),
                toggles.observe(QuestionnaireEnabled),
            ) { state, autoEnabled, questionsEnabled ->
                if (autoEnabled && questionsEnabled) state.autopilotTarget(chat, call) else null
            }.distinctUntilChanged().collectLatest { target ->
                if (target == null || provenance.manualAnswer.isCompleted || call.lifetime?.isActive == false) {
                    return@collectLatest
                }
                val input = buildStudioQuestionnaireContext(repository.value, chat, target) ?: return@collectLatest
                answerQuestions(questions, generations, input.context, machine, provenance) {
                    repository.value.questionnaireHumanContext(chat) == input.humanContext &&
                        isCurrent(call, chat, target, provenance.manualAnswer)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Questionnaire auto-answer failed; the questions remain available to the human" }
        }
    }

    private suspend fun answerQuestions(
        questions: List<Questionnaire>,
        generations: Map<QuestionnaireId, String>,
        context: ContinuationContext,
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        provenance: LiveAutopilotAnswerContext,
        isCurrent: suspend () -> Boolean,
    ) {
        for (question in questions.filter { it.id !in provenance.answered }) {
            if (!isCurrent()) return
            val expectedPending = generations.filterKeys { it !in provenance.answered }
            val answer = generate(question, context)
            if (!isCurrent()) return
            if (answer != null) {
                // Check the whole unanswered batch in the queue, before its outputs reach the collector.
                val result = machine.send(
                    QuestionnaireIntent.Public.Answer(
                        question.id,
                        answer,
                        provenance,
                        expectedGeneration = generations.getValue(question.id),
                        expectedPendingGenerations = expectedPending,
                    ),
                )
                if (result != SendResult.Accepted) break
                provenance.recordAnswer(question.id)
            }
        }
    }

    private suspend fun generate(question: Questionnaire, context: ContinuationContext): Answer? {
        val prompt = StudioQuestionnairePrompt.build(question, context) ?: return null
        val decision = when (val result = prompts.ask(prompt)) {
            is IsolatedAnswer.Answered -> StudioQuestionnairePrompt.parse(question, result.text)

            is IsolatedAnswer.Unavailable -> {
                log.w { "Questionnaire auto-answer unavailable: ${result.reason}" }
                null
            }
        }
        return (decision as? StudioQuestionnaireDecision.Answered)?.answer
    }

    private suspend fun isCurrent(
        call: AgentToolContext,
        chat: String,
        target: EngineTarget,
        manualAnswer: CompletableDeferred<Unit>,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (manualAnswer.isCompleted || call.lifetime?.isActive == false) return false
        return view.value.state.value.autopilotTarget(chat, call) == target &&
            toggles.get(AutoContinueEnabled) && toggles.get(QuestionnaireEnabled)
    }
}

private fun AutoContinueState.autopilotTarget(chat: String, call: AgentToolContext): EngineTarget? {
    val ready = this as? AutoContinueState.Ready ?: return null
    if (ready.chat(chat).mode != AutoContinueMode.Autopilot) return null
    return ready.settings.continuerTarget ?: call.target
}

private fun QuestionnaireState.generation(question: Questionnaire): String? {
    val asking = this as? QuestionnaireState.Asking ?: return null
    if (question.id in asking.submitting || question !in asking.pending) return null
    return asking.generations[question.id]
}

/** Per-call provenance: only outputs carrying this exact instance came from this call's autopilot. */
internal class LiveAutopilotAnswerContext : QuestionnaireAnswerContext {
    private val log = Log.tag("LiveAutopilotAnswerContext")
    val manualAnswer = CompletableDeferred<Unit>()
    private val acceptedAnswers = MutableStateFlow<Set<QuestionnaireId>>(emptySet())
    val answered: Set<QuestionnaireId> get() = acceptedAnswers.value

    // Both dispatch and collection record acceptance so cancellation after dispatch cannot lose it.
    fun recordAnswer(id: QuestionnaireId) {
        acceptedAnswers.update { it + id }
        log.v { "Recorded automatic questionnaire acceptance count=${acceptedAnswers.value.size}" }
    }
}

private data class LiveQuestionnaireWait(val isAccepted: Boolean, val isTimedOut: Boolean = false)

private class LiveQuestionnaireBatch(questions: List<PreparedQuestion>, sessionId: String) {
    private val owner = Uuid.random().toString()
    val asked = questions.associateWith { it.questionnaire(sessionId, owner) }
    val answers = HashMap<String, Answer>()
    val withdrawn = mutableSetOf<QuestionnaireId>()
    val automatic = mutableSetOf<String>()
    val autoContext = LiveAutopilotAnswerContext()
    val manualAnswer: CompletableDeferred<Unit> get() = autoContext.manualAnswer
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
        isFreeTextAllowed = true,
    )

    private fun question(): Question = when (args.kind) {
        QuestionKind.Confirm -> Question.Confirm(
            args.yes?.takeIf(String::isNotBlank) ?: "Да",
            args.no?.takeIf(String::isNotBlank) ?: "Нет",
        )

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

/** No deadline may decide a required answer or confirmation on the user's behalf. */
private fun List<PreparedQuestion>.timeoutMillis(): Long? {
    if (any { !it.args.isOptional || it.args.isImportant || it.args.kind == QuestionKind.Confirm }) return null
    return sumOf { question ->
        val args = question.args
        val reading = args.title.length.toLong() + (args.description?.length ?: 0) +
            args.choices.sumOf { it.title.length.toLong() }
        val answering = if (args.kind == QuestionKind.FreeText) TEXT_ANSWER_SECONDS else CHOICE_ANSWER_SECONDS
        answering + reading / READING_CHARACTERS_PER_SECOND
    }.coerceIn(CHOICE_ANSWER_SECONDS, MAX_WAIT_SECONDS) * MILLIS_PER_SECOND
}

private fun Answer.payload(): AnswerPayload = when (this) {
    is Answer.Confirmed -> AnswerPayload(isConfirmed = isConfirmed, text = text)
    is Answer.Selected -> AnswerPayload(selected = ids, text = text)
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
    val isImportant: Boolean = false,
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
private data class AskAnswers(
    val answers: Map<String, AnswerPayload>,
    val timedOut: List<String> = emptyList(),
    /** Generated choices are not human authorization, even when a confirmation is affirmative. */
    val autopilotAnswered: List<String> = emptyList(),
)

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
private const val CHOICE_ANSWER_SECONDS = 60L
private const val TEXT_ANSWER_SECONDS = 120L
private const val MAX_WAIT_SECONDS = 600L
private const val READING_CHARACTERS_PER_SECOND = 20L
private const val MILLIS_PER_SECOND = 1000L

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
          "isImportant": {
            "type": "boolean",
            "default": false,
            "description": "Keep an optional question open without a timeout. Required questions and Confirm already wait indefinitely."
          },
          "yes": {
            "type": "string",
            "description": "Confirm: optional yes label; defaults to Да when omitted or blank"
          },
          "no": {
            "type": "string",
            "description": "Confirm: optional no label; defaults to Нет when omitted or blank"
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
