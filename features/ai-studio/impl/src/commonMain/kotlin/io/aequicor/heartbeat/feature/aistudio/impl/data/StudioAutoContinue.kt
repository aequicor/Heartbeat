package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.decision
import io.aequicor.heartbeat.feature.aistudio.impl.domain.questionnaireId
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueHost
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMachineKey
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.autocontinue.api.AutopilotContextSettings
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationAnswer
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationRequest
import io.aequicor.heartbeat.feature.autocontinue.api.Phase
import io.aequicor.heartbeat.feature.checklist.api.Checklist
import io.aequicor.heartbeat.feature.checklist.api.ChecklistMachineKey
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission.Allow
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission.Drop
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDroppedException
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.Uuid

/** Learns about every turn the run coordinator executes. */
internal interface StudioTurnLifecycle {
    /** The turn of [request] is about to run. */
    suspend fun started(request: StudioTurnRequest)

    /** The turn of [request] ended with [outcome]. */
    suspend fun finished(request: StudioTurnRequest, outcome: RunOutcome)
}

/** Native turns of studio chats whose prompts the autopilot sent. */
internal fun interface StudioAutopilotMarks {
    /** Turn ids of the autopilot replies of [chat] over time. */
    fun observeAutopilotTurns(chat: String): Flow<Set<String>>
}

/** No lifecycle listener. */
internal object NoStudioTurnLifecycle : StudioTurnLifecycle {
    override suspend fun started(request: StudioTurnRequest) = Unit

    override suspend fun finished(request: StudioTurnRequest, outcome: RunOutcome) = Unit
}

/** Runs the turn [execute] of [request] and reports its start and end; an exception or cancellation is a failure. */
internal suspend fun StudioTurnLifecycle.observe(
    request: StudioTurnRequest,
    execute: suspend () -> RunOutcome,
): RunOutcome {
    var outcome = RunOutcome.Failed
    try {
        started(request)
        outcome = execute()
        return outcome
    } finally {
        withContext(NonCancellable) { finished(request, outcome) }
    }
}

/**
 * Connects studio chats with auto-continue. It reports the turns of ordinary chats (not helpers and organisms) to
 * the auto-continue machine: a completed turn is settled with the dialog for the continuer, or reported as waiting for
 * the human while a checklist, a proposed plan, a wake of the agent, a compaction or a questionnaire is open. Chats in
 * Off are not read at all; switching one on asks for its last turn ([resettle]). Context upkeep turns are not
 * reported as turns: after them a chat that paused or waits on is judged again ([isRejudgedAfterService]). As the
 * chat host it sends autopilot replies as turns of the chat (request ids `autopilot-…`, not human prompts) and
 * remembers their native turns, so the dialog and the transcript can mark them. Never throws into the turn.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioTurnLifecycle>())
@ContributesIntoSet(ProfileScope::class, binding = binding<AutoContinueHost>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioAutopilotMarks>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioAutopilotRequests>())
@Inject
internal class StudioAutoContinue(
    @ForScope(ProfileScope::class) stores: DataStores,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val view: Lazy<AutoContinueView>,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    // The repository depends on the coordinator, which reports turns here: deferred to break the cycle.
    private val runtime: Lazy<StudioRuntime>,
    // The coordinator reports turns here and runs the autopilot replies: deferred to break the cycle.
    private val runs: Lazy<StudioRunCoordinator>,
    private val host: Lazy<StudioRunHost>,
    private val planning: StudioPlanning,
    private val compactions: StudioCompactionPort,
    private val checklists: StudioChecklists,
    private val humanQueue: StudioQueuePort = NoStudioQueue,
) : StudioTurnLifecycle,
    StudioAutopilotMarks,
    StudioAutopilotRequests,
    AutoContinueHost {
    private val log = Log.tag("StudioAutoContinue")
    private val chats by lazy { stores.keyValue(ChatSpec) }
    private val marks by lazy { stores.keyValue(MarkSpec) }
    private val marksLock = Mutex()

    // The transcript reader keeps migration state: reads of parallel turns are serialized.
    private val transcriptsLock = Mutex()

    /** Read-only view of the transcripts the repository writes; the repository moves legacy carriers itself. */
    private val transcripts by lazy {
        StudioTranscripts(stores.database(StudioTranscriptDatabaseSpec).transcripts(), { emptyList() }, {})
    }

    /** Observes live tools and native permissions while their agent turn is still waiting for an answer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun observeQuestions() {
        combine(
            machines.observe(QuestionnaireMachineKey).flatMapLatest { it?.state ?: flowOf(QuestionnaireState.Idle) },
            view.value.state,
            runtime.value.state.map { it.permissions }.distinctUntilChanged(),
        ) { _, state, _ -> state }.collect { state ->
            val ready = state as? AutoContinueState.Ready ?: return@collect
            for ((chat, auto) in ready.chats) {
                if (auto.mode != AutoContinueMode.Autopilot || auto.phase is Phase.Suspended) continue
                guarded("pending request") { updateRequest(chat, auto, ready) }
            }
        }
    }

    private suspend fun updateRequest(chat: String, auto: ChatAuto, ready: AutoContinueState.Ready) {
        val asking = machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking
        val question = asking?.pending?.firstOrNull { it.source == chat && it.id !in asking.submitting }
        val old = auto.context?.request
        if (old != null && (question == null || request(question) != old)) {
            machines.send(AutoContinueMachineKey, Public.RequestClosed(chat, old.id))
        }
        if (question != null && request(question) != old) {
            val record = record(chat)?.takeIf { isTracked(it) } ?: return
            val context = contextFor(record, ready.settings.contextSettings, request(question)) ?: return
            machines.send(AutoContinueMachineKey, Public.RequestOpened(chat, context))
        }
    }

    override suspend fun answer(chat: String, request: ContinuationRequest, answer: ContinuationAnswer): Boolean {
        if (!toggles.get(AutoContinueEnabled)) return false
        val auto = autoChat(chat) ?: return false
        if (auto.mode != AutoContinueMode.Autopilot || auto.phase !is Phase.Delivering ||
            auto.context?.request != request
        ) {
            return false
        }
        val asking = machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking ?: return false
        val question = asking.pending.firstOrNull {
            it.source == chat && it.id.value == request.id && it.id !in asking.submitting
        } ?: return false
        val permission = runtime.value.state.value.permissions.firstOrNull { it.questionnaireId() == question.id }
        if (request(question, permission) != request) return false
        val resolved = question.autopilotAnswer(answer, permission) ?: run {
            log.w { "Autopilot answer does not fit the active question" }
            return false
        }
        if (!isSafeAnswer(question, resolved, permission)) return false
        return machines.send(
            QuestionnaireMachineKey,
            QuestionnaireIntent.Public.Answer(
                question.id,
                resolved,
                AutopilotQuestionContext(permission),
                expectedQuestionnaire = question,
            ),
        ) == SendResult.Accepted
    }

    private fun isSafeAnswer(question: Questionnaire, resolved: Answer, permission: StudioPermission?): Boolean {
        if (permission == null) return !question.id.value.startsWith("studio/")
        val option = permission.decision(resolved)?.optionId ?: return false
        return permission.options.any { it.id == option && it.isOneTime }
    }

    private fun request(
        question: Questionnaire,
        permission: StudioPermission? = runtime.value.state.value.permissions.firstOrNull {
            it.questionnaireId() ==
                question.id
        },
    ): ContinuationRequest {
        val metadata = permission?.options.orEmpty().map { option ->
            buildJsonObject {
                put("id", JsonPrimitive(option.id))
                put("isOneTime", JsonPrimitive(option.isOneTime))
                put("isSkip", JsonPrimitive(option.isSkip))
            }
        }
        val payload = buildJsonObject {
            put("question", Json.encodeToJsonElement(Questionnaire.serializer(), question))
            put("permissionOptions", JsonArray(metadata))
        }
        return ContinuationRequest(question.id.value, payload.toString())
    }

    override suspend fun started(request: StudioTurnRequest) = guarded("start") {
        val kind = request.turnKind()
        // Context upkeep keeps the dialog as it was: it neither cancels an answer nor counts as a new turn.
        if (kind == StudioTurnKind.Service) return@guarded
        val record = tracked(request) ?: return@guarded
        view.value // Starts the machine with the first tracked turn of the profile.
        machines.send(AutoContinueMachineKey, Public.TurnStarted(record.id, kind == StudioTurnKind.Human))
    }

    override suspend fun finished(request: StudioTurnRequest, outcome: RunOutcome) {
        val record = try {
            tracked(request) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "auto-continue: chat of a finished turn is unreadable" }
            return
        }
        val isService = request.turnKind() == StudioTurnKind.Service
        val chat = autoChat(record.id)
        // Off chats are never judged: nothing is read for them; a compaction re-judges only a waiting chat.
        val isSkipped = if (isService) {
            !isRejudgedAfterService(request, outcome, chat)
        } else {
            chat?.mode == AutoContinueMode.Off
        }
        if (isSkipped) return
        val intent = try {
            when {
                humanQueue.hasPending(record.id) -> Public.TurnAwaitsHuman(record.id)
                isService -> settled(record, isAfterService = true)
                outcome == RunOutcome.Stopped -> Public.TurnFailed(record.id, isStoppedByUser = true)
                outcome == RunOutcome.Failed -> Public.TurnFailed(record.id, isStoppedByUser = false)
                else -> settled(record, isAfterService = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The machine already left the previous answer behind: without a report the chat would wait forever.
            log.w(e) { "auto-continue: turn of ${record.id} could not be judged; the human decides" }
            Public.TurnAwaitsHuman(record.id)
        }
        log.i { "auto-continue: turn of ${record.id} ended $outcome" }
        guarded("finish") { machines.send(AutoContinueMachineKey, intent) }
    }

    /** Reports the last turn of [chat] again when it is idle; a running turn reports itself when it ends. */
    override suspend fun resettle(chat: String) {
        val record = record(chat)
        if (record == null || !isTracked(record)) return
        if (runs.value.isBusy(chat) || humanQueue.hasPending(chat)) return
        val intent = try {
            settled(record, isAfterService = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "auto-continue: last turn of $chat could not be judged" }
            Public.TurnAwaitsHuman(chat)
        }
        // A turn that started meanwhile reports itself, and a chat switched off again or answered meanwhile keeps its
        // state: an older answer must not overtake either.
        if (runs.value.isBusy(chat) || humanQueue.hasPending(chat) || !isAwaitingResettle(chat)) return
        log.i { "auto-continue: last turn of $chat reported again" }
        guarded("resettle") { machines.send(AutoContinueMachineKey, intent) }
    }

    override suspend fun deliver(chat: String, text: String): Boolean {
        if (humanQueue.hasPending(chat)) return false
        val record = record(chat)
        if (record == null || !isTracked(record)) return false
        val accepted = CompletableDeferred<Unit>()
        val applied = runtime.value.state.value.configurations[chat]?.applied
        val request = StudioTurnRequest(
            chat,
            prompt = text,
            settings = hostRunSettings(record, applied, runtime.value.defaults()),
            kind = WorktreeRunKind.Coding,
            request = RequestId("$AUTOPILOT_PREFIX${Uuid.random()}"),
            onTurnAccepted = { _, turn -> mark(chat, turn.value) },
            onAccepted = { accepted.complete(Unit) },
        )
        val run = profile.coroutineScope.async { runReply(request, text, accepted) }
        var outcome: RunOutcome? = null
        var isSettled = false
        try {
            outcome = select<RunOutcome?> {
                accepted.onAwait { null }
                run.onAwait { it }
            }
            isSettled = true
        } catch (e: ReplyWithdrawn) {
            // The human or Stop took the reply back while it waited: nothing was sent.
            log.w(e) { "autopilot reply to $chat withdrawn before its turn" }
            return false
        } finally {
            if (!isSettled && !accepted.isCompleted) run.cancel()
        }
        return (outcome == null || accepted.isCompleted).also { log.i { "autopilot reply to $chat accepted: $it" } }
    }

    /**
     * Runs the autopilot turn of [request] after an ending turn or a compaction, only while the machine still sends
     * [text]; otherwise it fails with [ReplyWithdrawn] before submission.
     */
    private suspend fun runReply(request: StudioTurnRequest, text: String, accepted: CompletableDeferred<Unit>) = try {
        runs.value.run(
            host.value,
            request,
            waitForIdle = true,
            cancelBeforeSubmission = true,
            beforeExecute = {
                if (humanQueue.hasPending(
                        request.id,
                    ) || !isSending(request.id, text)
                ) {
                    throw ReplyWithdrawn()
                }
            },
            admission = humanQueue.state.map { queue ->
                if (queue.chat(request.id).messages.isEmpty()) {
                    Allow
                } else {
                    Drop
                }
            },
        )
    } catch (e: ScheduledWakeDroppedException) {
        log.w(e) { "Human input took priority over an unsubmitted autopilot reply" }
        throw ReplyWithdrawn()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // After acceptance nobody awaits the run: its failure must reach the log here.
        if (accepted.isCompleted) log.w(e) { "autopilot turn of ${request.id} failed" }
        throw e
    }

    /** Native turn ids of the autopilot replies of [chat] over time. */
    override fun observeAutopilotTurns(chat: String): Flow<Set<String>> =
        marks.observe(MarksKey).map { it?.get(chat).orEmpty().toSet() }.distinctUntilChanged()

    /** Native turn ids of the autopilot replies of [chat]. */
    suspend fun autopilotTurns(chat: String): Set<String> = marks.get(MarksKey)?.get(chat).orEmpty().toSet()

    /**
     * The completed turn of [record] for the continuer, or a wait for the human. After context upkeep
     * ([isAfterService]) the compaction that just ran does not count as one still open.
     */
    private suspend fun settled(record: StudioChatRecord, isAfterService: Boolean): Public {
        val plan = planning.plans.first()[record.id]
        val end = StudioTurnEnd(
            isChecklistOpen = record.checklistReadiness(checklists.events.first()).first,
            plan = plan,
            hasWakes = (machines.find(SchedulerMachineKey)?.state?.value as? SchedulerState.Ready)
                ?.wakes.orEmpty().any { record.owns(it.session) },
            isCompacting = !isAfterService && record.id in compactions.compacting.first(),
            hasQuestions = (machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking)
                ?.pending.orEmpty().any { it.source == record.id },
        )
        val settings = (view.value.state.value as? AutoContinueState.Ready)?.settings?.contextSettings
            ?: AutopilotContextSettings()
        return when {
            end.hasQuestions -> currentQuestionIntent(record.id)

            end.hasWakes -> Public.TurnAwaitsEvent(record.id)

            end.isAwaitingHuman -> Public.TurnAwaitsHuman(record.id)

            else -> contextFor(record, settings)?.let { Public.TurnSettled(record.id, it) }
                ?: Public.TurnAwaitsHuman(record.id)
        }
    }

    /** Keep the profile observer's exact request instead of cancelling its current generation at turn end. */
    private fun currentQuestionIntent(chat: String): Public {
        val context = autoChat(chat)?.context
        return if (context?.request != null) Public.RequestOpened(chat, context) else Public.TurnAwaitsHuman(chat)
    }

    /** Every response path applies the same current source settings, including structured requests. */
    private suspend fun contextFor(
        record: StudioChatRecord,
        settings: AutopilotContextSettings,
        request: ContinuationRequest? = null,
    ): ContinuationContext? {
        val items = transcriptsLock.withLock { transcripts.read(record.id) }
        val context = continuationContext(
            record,
            items,
            autopilotTurns(record.id),
            planning.plans.first()[record.id],
            settings,
            request,
        )
            ?: return null
        val checklist = if (settings.isChecklistIncluded) checklistContext(record) else null
        return context.copy(
            extraContext = listOfNotNull(context.extraContext, checklist).joinToString("\n").ifBlank { null },
        )
    }

    private fun checklistContext(record: StudioChatRecord): String? {
        val state = machines.find(ChecklistMachineKey)?.state?.value
            as? ChecklistState.Ready
        return state?.journal?.cards.orEmpty().filter { it.session == record.ref }.takeLast(MAX_CHECKLISTS)
            .joinToString(
                "\n",
            ) { Json.encodeToString(Checklist.serializer(), it) }
            .ifBlank { null }
    }

    private suspend fun record(chat: String): StudioChatRecord? =
        chats.get(ChatsKey).orEmpty().firstOrNull { it.id == chat }

    /** Remembers an autopilot turn; a failed write only loses the label, never the accepted turn. */
    private suspend fun mark(chat: String, turn: String) = try {
        marksLock.withLock {
            val all = marks.get(MarksKey).orEmpty()
            val turns = (all[chat].orEmpty() + turn).takeLast(MAX_MARKS)
            marks.set(MarksKey, all + (chat to turns))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "autopilot turn of $chat was not marked" }
    }

    /** Whether the machine is still sending [text] to [chat]; a human message or Stop in between takes it back. */
    private fun isSending(chat: String, text: String): Boolean =
        (autoChat(chat)?.phase as? Phase.Delivering)?.text == text

    /** [chat] is on with nothing to answer and nothing running, as after a switch-on without a kept context. */
    private fun isAwaitingResettle(chat: String): Boolean = autoChat(chat)?.let {
        it.mode != AutoContinueMode.Off && it.phase == Phase.Idle && it.context == null
    } == true

    /** [chat] as the machine sees it; null until the machine has loaded. */
    private fun autoChat(chat: String): ChatAuto? = (view.value.state.value as? AutoContinueState.Ready)?.chat(chat)

    /** The autopilot reply was taken back before its turn started. */
    private class ReplyWithdrawn : IllegalStateException("The autopilot reply was taken back before its turn")

    /** The chat of [request] when auto-continue follows it: an ordinary chat turn, not a rollover. */
    private suspend fun tracked(request: StudioTurnRequest): StudioChatRecord? =
        record(request.id)?.takeIf { !request.isRollover && isTracked(it) }

    private suspend fun isTracked(record: StudioChatRecord): Boolean =
        record.helper == null && record.organismId == null && toggles.get(AutoContinueEnabled)

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "auto-continue $what notification failed" }
        }
    }

    private companion object {
        const val AUTOPILOT_PREFIX = "autopilot-"
        const val MAX_MARKS = 200
        const val MAX_CHECKLISTS = 10
        val MarkSpec = KeyValueSpec("ai_studio_autopilot")
        val MarksKey = jsonKey(
            "turns",
            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
        )
    }
}
