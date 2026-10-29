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
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionAnswerOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionContext
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.decision
import io.aequicor.heartbeat.feature.aistudio.impl.domain.followUpText
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ownsQuestionnaireId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.questionnaireId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.LIVE_QUESTION_ID_PREFIX
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.withContext

/**
 * Profile-owned delivery of engine permissions and restored questions. It uses the shared runtime without any
 * studio window. A live decision waits for native acknowledgement; a restored answer starts a profile-owned
 * follow-up with that chat's settings. Failed deliveries reopen the captured question. Live ask-user questions
 * retain their own owner. Claims are captured before launching concurrent deliveries, so another session can
 * answer a permission while a follow-up is still running. Started by [StudioQuestionStartup].
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioQuestionBridge(
    private val runtime: Lazy<StudioRuntime>,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val settings: Lazy<StudioChatSettings>,
) {
    private val log = Log.tag("StudioQuestions")
    private val lock = Mutex()

    private val live = mutableMapOf<QuestionnaireId, StudioPermission>()
    private val nativeGenerations = mutableMapOf<QuestionnaireId, String>()

    // Profile-lifetime provenance: an expired live request must never become a restored follow-up.
    private val nativeIds = mutableSetOf<QuestionnaireId>()
    private val deliveries = mutableMapOf<QuestionnaireId, Delivery>()
    private val followUps = mutableMapOf<String, Delivery>()

    private class Delivery(
        val question: Questionnaire,
        val generation: String,
        val permission: StudioPermission?,
        val isNative: Boolean,
        val environment: StudioChatEnvironment?,
        val autopilot: NativeAutopilotAnswerContext?,
    ) {
        var isAccepted = false
            private set
        fun accept() {
            isAccepted = true
        }
    }

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
                    if (isEnabled) {
                        combine(
                            runtime.value.state.map { it.permissions }.distinctUntilChanged(),
                            machines.observe(QuestionnaireMachineKey).flatMapLatest {
                                it?.state ?: flowOf(QuestionnaireState.Idle)
                            },
                        ) { permissions, _ -> permissions }
                    } else {
                        emptyFlow()
                    }
                }
                .collect { permissions -> guarded("sync permissions") { sync(permissions) } }
        }
        launch { deliverAnswers() }
    }

    private suspend fun sync(permissions: List<StudioPermission>) {
        val current = permissions.associateBy { it.questionnaireId() }
        // Machine intents are sent outside the lock: their outputs are consumed by [deliverAnswers].
        val (resolved, changed) = lock.withLock {
            val resolved = (live.keys - current.keys).associateWith { nativeGenerations.remove(it) }
            live.keys.retainAll(current.keys)
            val changed = current.values.filter { live[it.questionnaireId()] != it }
            changed.forEach {
                live[it.questionnaireId()] = it
                nativeIds += it.questionnaireId()
            }
            resolved to changed
        }
        resolved.forEach { (id, generation) ->
            log.i { "Engine resolved a question" }
            if (generation != null && pending(id)?.let { isCurrent(it, generation) } == true) {
                withdraw(id, generation)
            }
        }
        // Turn aliases may change across handles/restarts. Replace late journal copies, but never re-Ask the
        // current identity: that would clear its submitting flag while the answer is still in flight.
        current.values.forEach { permission ->
            val expected = permission.toQuestionnaire()
            if (permission.turnId != null && expected != null) {
                withdrawMatching {
                    it.id != expected.id && permission.ownsQuestionnaireId(it.id) && it == expected.copy(id = it.id)
                }
            }
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
    private suspend fun deliverAnswers() = coroutineScope {
        machines.observe(QuestionnaireMachineKey)
            .flatMapLatest { it?.outputs ?: emptyFlow() }
            .filterIsInstance<QuestionnaireOutput.Answered>()
            .collect { output ->
                val generation = output.generation ?: return@collect
                if (output.questionnaire.id.value.startsWith(LIVE_QUESTION_ID_PREFIX)) return@collect
                val delivery = prepareDelivery(output, generation) ?: return@collect
                launch { deliver(delivery, output.answer) }
            }
    }

    private suspend fun prepareDelivery(output: QuestionnaireOutput.Answered, generation: String): Delivery? {
        val question = output.questionnaire
        val delivery = lock.withLock {
            if (deliveries[question.id]?.generation == generation) return null
            if (question.id in deliveries || !isCurrent(question, generation)) {
                null
            } else {
                Delivery(
                    question,
                    generation,
                    live[question.id],
                    question.id in nativeIds,
                    (output.context as? StudioQuestionContext)?.environment,
                    output.context as? NativeAutopilotAnswerContext,
                ).also {
                    deliveries[question.id] = it
                }
            }
        }
        if (delivery == null && isCurrent(question, generation)) {
            // Answer already set submitting in the queue. A busy replacement must permit retry.
            guarded("reopen busy answer") { reopen(question, generation) }
        }
        return delivery
    }

    private suspend fun deliver(delivery: Delivery, answer: Answer) {
        val question = delivery.question
        try {
            if (!isCurrent(question, delivery.generation)) return
            val permission = delivery.permission
            val autopilot = delivery.autopilot
            if (autopilot != null) {
                // Structured contents are not authority. Generated answers never become restored follow-ups,
                // and cannot approve plain permissions even if a request id was reused.
                check(
                    permission?.input != null && permission == autopilot.permission &&
                        autopilot.isCurrent() && autopilot.isAuthorityCurrent(),
                ) {
                    "Native auto-answer is no longer current"
                }
                // Runtime delivery repeats these checks around its revocation barrier. Passing provenance
                // preserves questionnaire context there without restoring any action authority.
                currentCoroutineContext().ensureActive()
            }
            when {
                permission != null -> respondNatively(delivery, permission, answer)
                delivery.isNative -> log.i { "Ignore answer to an expired native request" }
                answer == Answer.Skipped -> withdrawCurrent(delivery)
                else -> followUp(delivery, answer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Failed to deliver an answer" }
            guarded("reopen a question") { reopenCurrent(delivery) }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (deliveries[question.id] === delivery) deliveries.remove(question.id)
                    if (followUps[question.source] === delivery) followUps.remove(question.source)
                }
            }
        }
    }

    private suspend fun respondNatively(delivery: Delivery, permission: StudioPermission, answer: Answer) {
        val question = delivery.question
        val current = runtime.value.state.value.permissions.firstOrNull { it.questionnaireId() == question.id }
        if (current != permission || permission.toQuestionnaire() != question) {
            log.i { "Ignore changed native request" }
            return
        }
        val decision = checkNotNull(permission.decision(answer)) { "Answer does not fit the native request" }
        log.i { "Deliver native permission answer" }
        val autopilot = delivery.autopilot
        if (autopilot == null) {
            runtime.value.respond(permission, decision.optionId, decision.answer)
        } else {
            val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
                permission,
                autopilot.isAuthorityCurrent,
                autopilot.isCurrent,
            )
            runtime.value.respond(permission, decision.optionId, decision.answer, origin)
        }
        // sync may already have replaced an identical-looking request with one from a later native turn.
        val currentAfterDelivery = runtime.value.state.value.permissions.firstOrNull {
            it.questionnaireId() == question.id
        }
        if (currentAfterDelivery == null || currentAfterDelivery == permission) withdrawCurrent(delivery)
    }

    private suspend fun followUp(delivery: Delivery, answer: Answer) {
        val question = delivery.question
        val isReserved = lock.withLock {
            if (question.source in followUps) {
                false
            } else {
                followUps[question.source] = delivery
                true
            }
        }
        check(isReserved) { "Session already runs a follow-up answer" }
        log.i { "Deliver restored answer as a profile-owned follow-up" }
        runtime.value.invalidateUserInstructions(question.source)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(question, delivery.generation)) return
        val outcome = runtime.value.run(
            question.source,
            question.followUpText(answer),
            settings.value.settings(question.source),
            attachments = emptyList(),
            environment = delivery.environment,
        ) {
            if (withdrawCurrent(delivery)) delivery.accept()
        }
        if (outcome == RunOutcome.Failed) reopenCurrent(delivery)
    }

    /** A stale completion must not withdraw a replacement with the same id, even if its contents match. */
    private suspend fun withdrawCurrent(delivery: Delivery): Boolean =
        withdraw(delivery.question.id, delivery.generation)

    private suspend fun withdrawMatching(predicate: (Questionnaire) -> Boolean) {
        val queue = machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking ?: return
        queue.pending.filter(predicate).forEach { question ->
            queue.generations[question.id]?.let { withdraw(question.id, it) }
        }
    }

    private fun isCurrent(question: Questionnaire, generation: String): Boolean {
        val queue = machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking ?: return false
        return queue.generations[question.id] == generation && question in queue.pending
    }

    /** Reopen only this delivery; never overwrite a replacement or resurrect an engine-resolved permission. */
    private suspend fun reopenCurrent(delivery: Delivery) {
        val question = delivery.question
        if (pending(question.id) != null && !isCurrent(question, delivery.generation)) return
        if (delivery.isNative) {
            val permission = runtime.value.state.value.permissions.firstOrNull { it.questionnaireId() == question.id }
            if (permission != delivery.permission) return
        }
        reopen(question, delivery.generation, isAbsentAllowed = delivery.isAccepted)
    }

    private fun pending(id: QuestionnaireId): Questionnaire? = pendingQuestions().firstOrNull { it.id == id }

    private fun pendingQuestions(): List<Questionnaire> =
        (machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking)?.pending.orEmpty()

    private suspend fun ask(questionnaire: Questionnaire) {
        val intent = QuestionnaireIntent.Public.Ask(questionnaire)
        lock.withLock { nativeGenerations[questionnaire.id] = intent.generation }
        val result = machines.send(QuestionnaireMachineKey, intent)
        if (result != SendResult.Accepted) log.w { "Question was not asked: $result" }
    }

    private suspend fun reopen(questionnaire: Questionnaire, generation: String, isAbsentAllowed: Boolean = false) {
        val intent = QuestionnaireIntent.Public.Ask(questionnaire, generation, isAbsentAllowed)
        val permission = lock.withLock { live[questionnaire.id] }
        val result = machines.send(QuestionnaireMachineKey, intent)
        if (result == SendResult.Accepted) {
            lock.withLock {
                if (
                    permission != null && live[questionnaire.id] == permission &&
                    isCurrent(questionnaire, intent.generation)
                ) {
                    nativeGenerations[questionnaire.id] = intent.generation
                }
            }
        }
        log.i { "Reopen undelivered question result=$result" }
    }

    private suspend fun withdraw(id: QuestionnaireId, generation: String): Boolean {
        val result = machines.send(QuestionnaireMachineKey, QuestionnaireIntent.Public.Withdraw(id, generation))
        log.i { "Withdraw question result=$result" }
        return result == SendResult.Accepted
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
    private val autopilot: Lazy<NativeQuestionnaireAutopilot>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            try {
                coroutineScope {
                    launch { bridge.value.run() }
                    launch { autopilot.value.run() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.tag("StudioQuestions").e(e) { "Question bridge stopped" }
            }
        }
    }
}
