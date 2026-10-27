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
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueHost
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMachineKey
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/** Learns about every turn the run coordinator executes. */
internal interface StudioTurnLifecycle {
    /** The turn of [request] is about to run. */
    suspend fun started(request: StudioTurnRequest)

    /** The turn of [request] ended with [outcome]. */
    suspend fun finished(request: StudioTurnRequest, outcome: RunOutcome)
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
 * the human while a checklist, a proposed plan, a wake of the agent, a compaction or a questionnaire is open. As the
 * chat host it sends autopilot replies as turns of the chat (request ids `autopilot-…`, not human prompts) and
 * remembers their native turns, so the dialog and the transcript can mark them. Never throws into the turn.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioTurnLifecycle>())
@ContributesIntoSet(ProfileScope::class, binding = binding<AutoContinueHost>())
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
) : StudioTurnLifecycle,
    AutoContinueHost {
    private val log = Log.tag("StudioAutoContinue")
    private val chats by lazy { stores.keyValue(ChatSpec) }
    private val marks by lazy { stores.keyValue(MarkSpec) }
    private val marksLock = Mutex()

    /** Read-only view of the transcripts the repository writes; the repository moves legacy carriers itself. */
    private val transcripts by lazy {
        StudioTranscripts(stores.database(StudioTranscriptDatabaseSpec).transcripts(), { emptyList() }, {})
    }

    override suspend fun started(request: StudioTurnRequest) = guarded("start") {
        val record = tracked(request) ?: return@guarded
        val isHuman = request.isUserPrompt || request.userInstruction != null
        machines.send(AutoContinueMachineKey, Public.TurnStarted(record.id, isHuman))
    }

    override suspend fun finished(request: StudioTurnRequest, outcome: RunOutcome) = guarded("finish") {
        val record = tracked(request)
        if (record == null || request.compaction != null || mode(record.id) == AutoContinueMode.Off) return@guarded
        val intent = when (outcome) {
            RunOutcome.Stopped -> Public.TurnFailed(record.id, isStoppedByUser = true)
            RunOutcome.Failed -> Public.TurnFailed(record.id, isStoppedByUser = false)
            RunOutcome.Completed -> settled(record)
        }
        log.i { "auto-continue: turn of ${record.id} ended $outcome" }
        machines.send(AutoContinueMachineKey, intent)
    }

    override suspend fun deliver(chat: String, text: String): Boolean {
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
        val run = profile.coroutineScope.async {
            try {
                runs.value.run(host.value, request, beforeExecute = {
                    check(record(chat)?.runRevision == record.runRevision) {
                        "The conversation changed before the autopilot reply"
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // After acceptance nobody awaits the run: its failure must reach the log here.
                log.w(e) { "autopilot turn of $chat failed" }
                throw e
            }
        }
        val outcome = select<RunOutcome?> {
            accepted.onAwait { null }
            run.onAwait { it }
        }
        return (outcome == null || accepted.isCompleted).also { log.i { "autopilot reply to $chat accepted: $it" } }
    }

    /** Native turn ids of the autopilot replies of [chat]. */
    suspend fun autopilotTurns(chat: String): Set<String> = marks.get(MarksKey)?.get(chat).orEmpty().toSet()

    private suspend fun settled(record: StudioChatRecord): Public {
        val plan = planning.plans.first()[record.id]
        val end = StudioTurnEnd(
            isChecklistOpen = record.checklistReadiness(checklists.events.first()).first,
            plan = plan,
            hasWakes = (machines.find(SchedulerMachineKey)?.state?.value as? SchedulerState.Ready)
                ?.wakes.orEmpty().any { record.owns(it.session) },
            isCompacting = record.id in compactions.compacting.first(),
            hasQuestions = (machines.find(QuestionnaireMachineKey)?.state?.value as? QuestionnaireState.Asking)
                ?.pending.orEmpty().any { it.source == record.id },
        )
        if (end.isAwaitingHuman) return Public.TurnAwaitsHuman(record.id)
        val context = continuationContext(record, transcripts.read(record.id), autopilotTurns(record.id), plan)
        return if (context == null) Public.TurnAwaitsHuman(record.id) else Public.TurnSettled(record.id, context)
    }

    private suspend fun record(chat: String): StudioChatRecord? =
        chats.get(ChatsKey).orEmpty().firstOrNull { it.id == chat }

    private suspend fun mark(chat: String, turn: String) = marksLock.withLock {
        val all = marks.get(MarksKey).orEmpty()
        val turns = (all[chat].orEmpty() + turn).takeLast(MAX_MARKS)
        marks.set(MarksKey, all + (chat to turns))
    }

    /** The chat of [request] when auto-continue follows it: an ordinary chat turn, not a rollover. */
    private suspend fun tracked(request: StudioTurnRequest): StudioChatRecord? =
        record(request.id)?.takeIf { !request.isRollover && isTracked(it) }

    private suspend fun isTracked(record: StudioChatRecord): Boolean =
        record.helper == null && record.organismId == null && toggles.get(AutoContinueEnabled)

    /** The chat's mode; reading the view starts the machine with the first tracked turn of the profile. */
    private fun mode(chat: String): AutoContinueMode? = (view.value.state.value as? AutoContinueState.Ready)
        ?.chat(chat)?.mode

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
        val MarkSpec = KeyValueSpec("ai_studio_autopilot")
        val MarksKey = jsonKey(
            "turns",
            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
        )
    }
}
