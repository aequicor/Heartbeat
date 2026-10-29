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
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionAnswerOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionContext
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRunAdmission
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
 * follow-up with that chat's settings. The captured queue admits it at the runtime submission boundary;
 * receipts recover failed accepted deliveries unless an explicit mutation revoked their ownership. Live ask-user
 * questions retain their own owner. Claims are captured before launching concurrent deliveries, so another session can
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
    private val nativeSync = Mutex()

    private val live = mutableMapOf<QuestionnaireId, StudioPermission>()
    private val nativeGenerations = mutableMapOf<QuestionnaireId, String>()

    // Profile-lifetime provenance: an expired live request must never become a restored follow-up.
    private val nativeIds = mutableSetOf<QuestionnaireId>()
    private val deliveries = mutableMapOf<QuestionnaireId, Delivery>()
    private val followUps = mutableMapOf<String, Delivery>()

    private class Delivery(
        val machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        val question: Questionnaire,
        val generation: String,
        val permission: StudioPermission?,
        val isNative: Boolean,
        val environment: StudioChatEnvironment?,
        val autopilot: NativeAutopilotAnswerContext?,
    ) {
        private val admissionLock = Mutex()
        private var isAdmissionClosed = false
        var isAdmissionAttempted = false
            private set
        var isAdmitted = false
            private set
        var acceptance: QuestionnaireIntent.Public.AcceptDelivery? = null
        var isAccepted = false

        /**
         * The profile worker can outlive the bridge caller. Serialize its short queue claim with cleanup,
         * including publication of the result. Never hold this lock across preparation or native IO.
         */
        suspend fun admit(): Boolean = admissionLock.withLock {
            if (isAdmissionClosed) return@withLock false
            currentCoroutineContext().ensureActive()
            isAdmissionAttempted = true
            // Record ownership before cancellation can interrupt the return from send. An ignored duplicate
            // or a lost reply is not proof of ownership and must never fail another collector's gate.
            withContext(NonCancellable) {
                val result = machine.send(QuestionnaireIntent.Public.BeginDelivery(question.id, generation))
                (result == SendResult.Accepted).also { if (it) isAdmitted = true }
            }
        }

        /** Wait only for an entered claim, then prevent even a not-yet-started worker from claiming later. */
        suspend fun closeAdmission() = admissionLock.withLock {
            isAdmissionClosed = true
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

    private suspend fun sync(permissions: List<StudioPermission>) = nativeSync.withLock {
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
            .flatMapLatest { machine ->
                machine?.outputs?.filterIsInstance<QuestionnaireOutput.Answered>()?.map { machine to it } ?: emptyFlow()
            }
            .collect { (machine, output) ->
                val generation = output.generation ?: return@collect
                if (output.questionnaire.id.value.startsWith(LIVE_QUESTION_ID_PREFIX)) return@collect
                val delivery = prepareDelivery(machine, output, generation) ?: return@collect
                launch { deliver(delivery, output.answer) }
            }
    }

    private suspend fun prepareDelivery(
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        output: QuestionnaireOutput.Answered,
        generation: String,
    ): Delivery? {
        val question = output.questionnaire
        val delivery = lock.withLock {
            if (deliveries[question.id]?.generation == generation) return null
            if (question.id in deliveries || !isCurrent(question, generation, machine)) {
                null
            } else {
                Delivery(
                    machine,
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
        if (delivery == null && isCurrent(question, generation, machine)) {
            // Answer already set submitting in the queue. A busy replacement must permit retry.
            guarded("reopen busy answer") { reopen(machine, question, generation) }
        }
        return delivery
    }

    private suspend fun deliver(delivery: Delivery, answer: Answer) {
        val question = delivery.question
        var isSuccessful = false
        var hasFailed = false
        try {
            if (!isCurrent(question, delivery.generation, delivery.machine)) return
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
            isSuccessful = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hasFailed = true
            log.e(e) { "Failed to deliver an answer" }
        } finally {
            withContext(NonCancellable) {
                finishDelivery(delivery, isSuccessful, hasFailed)
            }
        }
    }

    private suspend fun finishDelivery(delivery: Delivery, isSuccessful: Boolean, hasFailed: Boolean) {
        try {
            delivery.closeAdmission()
            if (hasFailed || (!isSuccessful && delivery.isAdmissionAttempted)) recover(delivery)
        } finally {
            withContext(NonCancellable) {
                releaseDelivery(delivery)
            }
        }
    }

    private suspend fun releaseDelivery(delivery: Delivery) = lock.withLock {
        val question = delivery.question
        if (deliveries[question.id] === delivery) deliveries.remove(question.id)
        if (followUps[question.source] === delivery) followUps.remove(question.source)
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
        if (!isCurrent(question, delivery.generation, delivery.machine)) return
        val outcome = runtime.value.run(
            question.source,
            question.followUpText(answer),
            settings.value.settings(question.source),
            attachments = emptyList(),
            environment = delivery.environment,
            admission = StudioRunAdmission {
                val isAdmitted = delivery.admit()
                currentCoroutineContext().ensureActive()
                isAdmitted
            },
        ) {
            check(delivery.isAdmitted) { "Question delivery was not admitted" }
            check(delivery.acceptance == null) { "Question delivery acceptance was already attempted" }
            val acceptance = QuestionnaireIntent.Public.AcceptDelivery(question.id, delivery.generation)
            // send may commit before it throws or observes cancellation: retain the receipt first.
            delivery.acceptance = acceptance
            val result = delivery.machine.send(acceptance)
            if (result != SendResult.Accepted) delivery.acceptance = null
            check(result == SendResult.Accepted && !isCurrent(question, delivery.generation, delivery.machine)) {
                "Question delivery acceptance failed: $result"
            }
            delivery.isAccepted = true
        }
        if (outcome == RunOutcome.Completed && delivery.isAccepted) {
            val acceptance = checkNotNull(delivery.acceptance)
            withContext(NonCancellable) {
                delivery.machine.send(QuestionnaireIntent.Public.CompleteDelivery(question.id, acceptance.receiptToken))
            }
        } else {
            recover(delivery)
        }
    }

    /** A stale completion must not withdraw a replacement with the same id, even if its contents match. */
    private suspend fun withdrawCurrent(delivery: Delivery): Boolean = delivery.machine.send(
        QuestionnaireIntent.Public.Withdraw(delivery.question.id, delivery.generation),
    ) == SendResult.Accepted

    private suspend fun withdrawMatching(predicate: (Questionnaire) -> Boolean) {
        val queue = machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking ?: return
        queue.pending.filter(predicate).forEach { question ->
            queue.generations[question.id]?.let { withdraw(question.id, it) }
        }
    }

    private fun isCurrent(
        question: Questionnaire,
        generation: String,
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>? =
            machines.find(QuestionnaireMachineKey),
    ): Boolean {
        val queue = machine?.state?.value as? QuestionnaireState.Asking ?: return false
        return queue.generations[question.id] == generation && question in queue.pending
    }

    /** Cleanup survives profile cancellation; the captured queue alone decides whether recovery is still owned. */
    private suspend fun recover(delivery: Delivery) = withContext(NonCancellable) {
        guarded("reopen a question") { reopenCurrent(delivery) }
    }

    /** Reopen only this delivery; never overwrite a replacement or resurrect an engine-resolved permission. */
    private suspend fun reopenCurrent(delivery: Delivery) {
        val question = delivery.question
        if (delivery.isAdmitted) {
            delivery.machine.send(
                QuestionnaireIntent.Public.FailDelivery(
                    question.id,
                    delivery.generation,
                    delivery.acceptance?.receiptToken,
                ),
            )
        } else {
            // The queue atomically distinguishes an unclaimed answer from another collector's gate.
            reopen(delivery.machine, question, delivery.generation)
        }
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

    /** Serialize native retry publication with resolution, including its generation bookkeeping. */
    private suspend fun reopen(
        machine: MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput>,
        questionnaire: Questionnaire,
        generation: String,
    ) = nativeSync.withLock {
        if (!isCurrent(questionnaire, generation, machine)) return@withLock
        val permission = lock.withLock { live[questionnaire.id] }
        val isNative = lock.withLock { questionnaire.id in nativeIds }
        if (isNative && (permission == null || !isLive(permission))) return@withLock
        val intent = QuestionnaireIntent.Public.ReopenUndelivered(questionnaire.id, generation)
        val result = machine.send(intent)
        if (result == SendResult.Accepted && permission != null) {
            lock.withLock { nativeGenerations[questionnaire.id] = intent.generation }
            // Runtime state may resolve while dispatch is suspended, before sync can acquire nativeSync.
            if (!isLive(permission)) {
                machine.send(QuestionnaireIntent.Public.Withdraw(questionnaire.id, intent.generation))
            }
        }
        log.i { "Reopen undelivered question result=$result" }
    }

    private fun isLive(permission: StudioPermission): Boolean =
        runtime.value.state.value.permissions.any { it == permission }

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
