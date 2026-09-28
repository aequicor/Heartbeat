package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.decision
import io.aequicor.heartbeat.feature.aistudio.impl.domain.followUpText
import io.aequicor.heartbeat.feature.aistudio.impl.domain.questionnaireId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Profile-owned bridge between engine permissions and the questionnaire (toggle `questionnaire.enabled`).
 * Pending permissions are asked as questions of their session. A question is withdrawn only when the engine
 * resolves its request while observed, or after the user's answer; closing screens never withdraws it.
 * Questions restored after a restart may have lost their native request: their answer is sent to the session
 * as a follow-up message instead. Confined to the profile scope's dispatcher.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioQuestionBridge(
    private val runtime: StudioRuntime,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("StudioQuestions")

    // Permissions seen pending during live observation; only these can be withdrawn by the engine.
    private val live = mutableMapOf<QuestionnaireId, StudioPermission>()
    private var job: Job? = null

    /** Sessions with open questions while the questionnaire is enabled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sources: Flow<Set<String>> = combine(
        toggles.observe(QuestionnaireEnabled),
        machines.observe(QuestionnaireMachineKey).flatMapLatest { it?.state ?: flowOf(QuestionnaireState.Idle) },
    ) { isEnabled, state ->
        val pending = (state as? QuestionnaireState.Asking)?.pending.orEmpty()
        if (isEnabled) pending.mapTo(mutableSetOf()) { it.source } else emptySet()
    }.distinctUntilChanged()

    /** Starts the bridge once for the profile lifetime (when a studio first shows questions). */
    fun start() {
        if (job != null) return
        log.i { "Start questionnaire bridge" }
        job = profile.coroutineScope.launch { run() }
    }

    /** Syncs and delivers answers until cancelled. */
    suspend fun run() = coroutineScope {
        launch {
            combine(toggles.observe(QuestionnaireEnabled), runtime.state) { isEnabled, state ->
                if (isEnabled) state.permissions else null
            }.distinctUntilChanged().collect { permissions -> permissions?.let { sync(it) } }
        }
        launch { deliverAnswers() }
    }

    private suspend fun sync(permissions: List<StudioPermission>) {
        val current = permissions.associateBy { it.questionnaireId() }
        (live.keys - current.keys).forEach { id ->
            live.remove(id)
            log.i { "Engine resolved a question" }
            machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Withdraw(id))
        }
        current.forEach { (id, permission) ->
            if (live[id] != permission) {
                live[id] = permission
                ask(permission.toQuestionnaire())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun deliverAnswers() {
        machines.observe(QuestionnaireMachineKey)
            .flatMapLatest { it?.outputs ?: emptyFlow() }
            .filterIsInstance<QuestionnaireOutput.Answered>()
            .collect { answered ->
                val permission = live[answered.questionnaire.id]
                if (permission == null || !respondNatively(permission, answered.answer)) {
                    followUp(answered.questionnaire, answered.answer)
                }
            }
    }

    /** Answers the pending native request; false when it no longer exists. */
    private suspend fun respondNatively(permission: StudioPermission, answer: Answer): Boolean {
        val decision = permission.decision(answer) ?: return false
        return try {
            log.i { "Answer native permission request=${permission.requestId}" }
            runtime.respond(permission.sessionId, permission.requestId, decision.optionId, decision.answer)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            log.w(e) { "Native permission request is gone; sending the answer as a message" }
            false
        }
    }

    /** Sends the answer as the next user message of the session and closes the question. */
    private suspend fun followUp(questionnaire: Questionnaire, answer: Answer) {
        log.i { "Deliver answer as a follow-up message kind=${answer::class.simpleName.orEmpty()}" }
        live.remove(questionnaire.id)
        machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Withdraw(questionnaire.id))
        profile.coroutineScope.launch {
            val outcome = runtime.run(questionnaire.source, questionnaire.followUpText(answer), runtime.defaults())
            log.i { "Follow-up answer finished outcome=${outcome.name}" }
        }
    }

    private suspend fun ask(questionnaire: Questionnaire) {
        val result = machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Ask(questionnaire))
        if (result != SendResult.Accepted) log.w { "Question was not asked: $result" }
    }
}
