package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineKey
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Profile-owned bridge between engine permissions and the questionnaire (toggle `questionnaire.enabled`).
 * Pending permissions are asked as questions of their session. Answers go to the studio machine
 * (`AiStudioMachineKey`): a live request is answered with `RespondPermission`, a question restored after a restart
 * whose native request is gone is answered with a `FollowUp` message (a skipped one is just withdrawn).
 * An accepted answer withdraws its question; an undeliverable one reopens it, and so does an accepted one whose
 * delivery fails later (`PermissionAnswerFailed`, a follow-up run ending `Failed`). Answers reach the studio machine
 * only while the studio screen runs it; otherwise their questions reopen. Failures are logged and never stop
 * the bridge. Started with the profile ([StudioQuestionStartup]).
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioQuestionBridge(
    private val runtime: Lazy<StudioRuntime>,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
) {
    private val log = Log.tag("StudioQuestions")
    private val lock = Mutex()

    // Permissions seen pending during live observation and the ones already answered; guarded by [lock].
    private val live = mutableMapOf<QuestionnaireId, StudioPermission>()
    private val answered = mutableSetOf<QuestionnaireId>()

    // Restored questions answered by a follow-up run of their session that has not ended yet; guarded by [lock].
    private val followUps = mutableMapOf<String, Questionnaire>()

    /** Sessions with open questions while the questionnaire is enabled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sources: Flow<Set<String>> = combine(
        toggles.observe(QuestionnaireEnabled),
        machines.observe(QuestionnaireMachineKey).flatMapLatest { it?.state ?: flowOf(QuestionnaireState.Idle) },
    ) { isEnabled, state ->
        val pending = (state as? QuestionnaireState.Asking)?.pending.orEmpty()
        if (isEnabled) pending.mapTo(mutableSetOf()) { it.source } else emptySet()
    }.distinctUntilChanged()

    /** Syncs and delivers answers until cancelled; runtime permissions are observed only while enabled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() = coroutineScope {
        log.i { "Start questionnaire bridge" }
        launch {
            toggles.observe(QuestionnaireEnabled)
                .flatMapLatest { isEnabled ->
                    if (isEnabled) runtime.value.state.map { it.permissions } else emptyFlow()
                }
                .distinctUntilChanged()
                .collect { permissions -> guarded("sync permissions") { sync(permissions) } }
        }
        launch { deliverAnswers() }
        launch { watchDeliveries() }
    }

    private suspend fun sync(permissions: List<StudioPermission>) {
        val current = permissions.associateBy { it.questionnaireId() }
        // Machine intents are sent outside the lock: their outputs are consumed by [deliverAnswers].
        val (resolved, changed) = lock.withLock {
            val resolved = (live.keys - current.keys).filterNot { answered.remove(it) }
            live.keys.retainAll(current.keys)
            val changed = current.values.filter { live[it.questionnaireId()] != it }
            changed.forEach { live[it.questionnaireId()] = it }
            answered.removeAll(changed.map { it.questionnaireId() }.toSet())
            resolved to changed
        }
        resolved.forEach { id ->
            log.i { "Engine resolved a question" }
            withdraw(id)
        }
        changed.forEach { permission ->
            val questionnaire = permission.toQuestionnaire()
            if (questionnaire == null) {
                log.w { "Permission offers no answerable options request=${permission.requestId}" }
            } else {
                ask(questionnaire)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun deliverAnswers() {
        machines.observe(QuestionnaireMachineKey)
            .flatMapLatest { it?.outputs ?: emptyFlow() }
            .filterIsInstance<QuestionnaireOutput.Answered>()
            .collect { answered -> deliver(answered.questionnaire, answered.answer) }
    }

    private suspend fun deliver(questionnaire: Questionnaire, answer: Answer) {
        val isDelivered = try {
            val permission = lock.withLock { live[questionnaire.id]?.takeIf { questionnaire.id !in answered } }
            when {
                permission != null -> respondNatively(permission, answer)
                answer == Answer.Skipped -> true.also { log.i { "Restored question skipped" } }
                else -> followUp(questionnaire, answer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Failed to deliver an answer" }
            false
        }
        guarded(if (isDelivered) "withdraw a question" else "reopen a question") {
            if (isDelivered) withdraw(questionnaire.id) else reopen(questionnaire)
        }
    }

    /** Reopens questions whose accepted answer failed to reach the engine. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchDeliveries() {
        machines.observe(AiStudioMachineKey)
            .flatMapLatest { it?.outputs ?: emptyFlow() }
            .collect { output -> guarded("watch a delivery") { onDelivery(output) } }
    }

    private suspend fun onDelivery(output: AiStudioOutput) {
        val failed = lock.withLock {
            when (output) {
                is AiStudioOutput.PermissionAnswerFailed ->
                    live.values
                        .firstOrNull { it.sessionId == output.sessionId && it.requestId == output.requestId }
                        ?.takeIf { answered.remove(it.questionnaireId()) }
                        ?.toQuestionnaire()

                is AiStudioOutput.RunEnded ->
                    followUps.remove(output.sessionId)?.takeIf { output.outcome == RunOutcome.Failed }

                is AiStudioOutput.SubmitFailed -> null
            }
        }
        if (failed != null) {
            log.w { "Answer was not delivered, reopen its question" }
            reopen(failed)
        }
    }

    /** Answers the pending native request through the studio machine; false when the answer does not fit it. */
    private suspend fun respondNatively(permission: StudioPermission, answer: Answer): Boolean {
        val decision = permission.decision(answer)
        if (decision == null) {
            log.w { "Answer does not fit the pending request=${permission.requestId}" }
            return false
        }
        log.i { "Answer native permission request=${permission.requestId}" }
        val result = machines.send(
            AiStudioMachineKey,
            AiStudioIntent.Public.RespondPermission(
                permission.sessionId,
                permission.requestId,
                decision.optionId,
                decision.answer,
            ),
        )
        if (result != SendResult.Accepted) {
            log.w { "Permission answer was not accepted: $result" }
            return false
        }
        lock.withLock { answered += permission.questionnaireId() }
        return true
    }

    /** Sends the answer as the next user message of the session; false while the studio cannot run it. */
    private suspend fun followUp(questionnaire: Questionnaire, answer: Answer): Boolean {
        log.i { "Deliver answer as a follow-up message kind=${answer::class.simpleName.orEmpty()}" }
        // Registered before sending: the run may end before [send] returns.
        lock.withLock { followUps[questionnaire.source] = questionnaire }
        var result: SendResult? = null
        try {
            result = machines.send(
                AiStudioMachineKey,
                AiStudioIntent.Public.FollowUp(questionnaire.source, questionnaire.followUpText(answer)),
            )
        } finally {
            if (result != SendResult.Accepted) lock.withLock { followUps.remove(questionnaire.source) }
        }
        if (result != SendResult.Accepted) log.w { "Follow-up answer was not accepted: $result" }
        return result == SendResult.Accepted
    }

    private suspend fun ask(questionnaire: Questionnaire) {
        val result = machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Ask(questionnaire))
        if (result != SendResult.Accepted) log.w { "Question was not asked: $result" }
    }

    private suspend fun reopen(questionnaire: Questionnaire) {
        val result = machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Ask(questionnaire))
        log.i { "Reopen undelivered question result=$result" }
    }

    private suspend fun withdraw(id: QuestionnaireId) {
        val result = machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Withdraw(id))
        log.i { "Withdraw question result=$result" }
    }

    private suspend fun guarded(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Question bridge failed to $action" }
        }
    }
}

/** Starts [StudioQuestionBridge] with the profile, before any studio screen shows its questions. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioQuestionStartup(
    private val bridge: Lazy<StudioQuestionBridge>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            try {
                bridge.value.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.tag("StudioQuestions").e(e) { "Question bridge stopped" }
            }
        }
    }
}
