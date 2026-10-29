package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Profile-owned answering of live native structured questions, without a studio screen. Plain permission buttons
 * and restored questions never reach the model. Each attempt is tied to the native turn, queue generation and
 * current model route. Completed attempts stay remembered only while their exact candidate remains eligible.
 * Structured input is untrusted data, not authorization: the shared prompt fences its contents and the bridge
 * revokes human authority before delivering an answer carrying [NativeAutopilotAnswerContext].
 */
@SingleIn(ProfileScope::class)
@Inject
internal class NativeQuestionnaireAutopilot(
    private val runtime: Lazy<StudioRuntime>,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val settings: Lazy<StudioChatSettings>,
    private val view: Lazy<AutoContinueView>,
    private val prompts: IsolatedPrompts,
    private val repository: Lazy<StudioRepository>,
) {
    private val log = Log.tag("NativeQuestionnaireAutopilot")

    suspend fun run() {
        try {
            observe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Native auto-answer observation stopped; manual delivery remains available" }
        }
    }

    private suspend fun observe() = coroutineScope {
        val attempts = mutableMapOf<Candidate, Job>()
        // A failed delivery may reopen a new queue generation. Leave that retry to the human, avoiding AI loops.
        val submitted = MutableStateFlow(emptySet<StudioPermission>())
        candidates().distinctUntilChanged().collect { candidates ->
            if (submitted.value.isNotEmpty()) {
                val live = runtime.value.state.value.permissions.toSet()
                submitted.update { it.intersect(live) }
            }
            val removed = attempts.keys - candidates.toSet()
            removed.forEach { attempts.remove(it)?.cancel() }
            candidates.forEach { candidate ->
                if (candidate !in attempts && candidate.permission !in submitted.value) {
                    attempts[candidate] = launch { answer(candidate, submitted) }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun candidates(): Flow<List<Candidate>> =
        combine(toggles.observe(QuestionnaireEnabled), toggles.observe(AutoContinueEnabled)) { questions, auto ->
            questions && auto
        }.flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(emptyList())
            } else {
                machines.observe(QuestionnaireMachineKey).flatMapLatest { machine ->
                    if (machine == null) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            runtime.value.state,
                            machine.state,
                            view.value.state,
                            repository.value.observeWorkspace(),
                            runtime.value.observeDefaults(),
                        ) { state, queue, auto, _, _ ->
                            candidates(machine, state.permissions, queue, auto)
                        }
                    }
                }
            }
        }

    private suspend fun candidates(
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        permissions: List<StudioPermission>,
        queue: QuestionnaireState,
        auto: AutoContinueState,
    ): List<Candidate> {
        val asking = queue as? QuestionnaireState.Asking ?: return emptyList()
        return permissions.mapNotNull { permission ->
            if (permission.input == null || permission.turnId.isNullOrBlank()) return@mapNotNull null
            val question = permission.toQuestionnaire() ?: return@mapNotNull null
            if (question !in asking.pending || question.id in asking.submitting) return@mapNotNull null
            val generation = asking.generations[question.id] ?: return@mapNotNull null
            val target = target(permission.sessionId, auto) ?: return@mapNotNull null
            Candidate(permission, question, generation, target, machine)
        }
    }

    private suspend fun target(chat: String, auto: AutoContinueState = view.value.state.value): EngineTarget? {
        val ready = auto as? AutoContinueState.Ready ?: return null
        if (ready.chat(chat).mode != AutoContinueMode.Autopilot) return null
        return try {
            ready.settings.continuerTarget ?: turnTarget(settings.value.settings(chat).modelId, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Native questionnaire target is unavailable" }
            null
        }
    }

    private suspend fun answer(candidate: Candidate, submitted: MutableStateFlow<Set<StudioPermission>>) {
        try {
            val context = buildStudioQuestionnaireContext(
                repository.value,
                candidate.permission.sessionId,
                candidate.target,
            ) ?: return
            val prompt = StudioQuestionnairePrompt.build(candidate.question, context.context) ?: return
            val isHumanContextCurrent: suspend () -> Boolean = {
                repository.value.questionnaireHumanContext(candidate.permission.sessionId) == context.humanContext
            }
            if (!isCurrent(candidate) || !isHumanContextCurrent()) return
            val result = prompts.ask(prompt)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(candidate) || !isHumanContextCurrent()) return
            val decision = when (result) {
                is IsolatedAnswer.Answered -> StudioQuestionnairePrompt.parse(candidate.question, result.text)

                is IsolatedAnswer.Unavailable -> {
                    log.w { "Native questionnaire auto-answer unavailable: ${result.reason}" }
                    null
                }
            }
            if (decision !is StudioQuestionnaireDecision.Answered) return
            submitted.update { it + candidate.permission }
            candidate.machine.send(
                QuestionnaireIntent.Public.Answer(
                    candidate.question.id,
                    decision.answer,
                    NativeAutopilotAnswerContext(
                        candidate.permission,
                        candidate.generation,
                        isHumanContextCurrent,
                    ) {
                        isCurrent(candidate, submitting = true) && isHumanContextCurrent()
                    },
                    expectedGeneration = candidate.generation,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Native questionnaire auto-answer failed; the question remains available to the human" }
        }
    }

    private suspend fun isCurrent(candidate: Candidate, submitting: Boolean = false): Boolean {
        val auto = view.value.state.value
        if (target(candidate.permission.sessionId, auto) != candidate.target ||
            !toggles.get(QuestionnaireEnabled) || !toggles.get(AutoContinueEnabled)
        ) {
            return false
        }
        currentCoroutineContext().ensureActive()
        val queue = candidate.machine.state.value as? QuestionnaireState.Asking ?: return false
        return view.value.state.value == auto && machines.find(QuestionnaireMachineKey) === candidate.machine &&
            candidate.permission in runtime.value.state.value.permissions &&
            candidate.question in queue.pending && queue.generations[candidate.question.id] == candidate.generation &&
            (submitting || candidate.question.id !in queue.submitting)
    }

    private data class Candidate(
        val permission: StudioPermission,
        val question: Questionnaire,
        val generation: String,
        val target: EngineTarget,
        val machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
    )
}

/**
 * Host-only provenance. [isAuthorityCurrent] validates the questionnaire's human context snapshot, not action
 * authority. Automatic delivery revokes action authority while preserving that context for subsequent questions.
 * [isCurrent] rechecks eligibility and the same context both before and after that deliberate revocation.
 * Neither callback grants fresh human authorization.
 */
internal class NativeAutopilotAnswerContext(
    val permission: StudioPermission,
    val generation: String,
    val isAuthorityCurrent: suspend () -> Boolean,
    val isCurrent: suspend () -> Boolean,
) : QuestionnaireAnswerContext
