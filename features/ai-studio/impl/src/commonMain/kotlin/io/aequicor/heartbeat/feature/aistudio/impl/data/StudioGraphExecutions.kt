package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.answerOf
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class GraphChatAttempt(
    val chat: String,
    val result: GraphTaskResult? = null,
    val checkpoint: String? = null,
    val recoveryRoot: String? = null,
    val causes: Set<RequestInitiator> = emptySet(),
    val native: GraphNativeIdentity? = null,
    val work: StudioGraphWorkSnapshot? = null,
)

/** Exact source of a graph result; an observer execution may differ from the native request. */
@Serializable
internal data class GraphNativeIdentity(val session: SessionRef, val request: RequestId, val turn: TurnId?)

/** Native graph submissions and results survive a crash between studio completion and scheduler acknowledgement. */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioGraphExecutions(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val runtime: StudioRuntime,
    private val runs: StudioRunCoordinator,
    private val host: StudioRunHost,
    private val facade: EngineFacade,
    private val attempts: StudioHelperAttempts,
    private val observations: StudioConversationObservations,
    private val efforts: EffortChoicesView,
) {
    private val log = Log.tag("StudioGraphExecutions")
    private val chats = stores.keyValue(ChatSpec)
    private val journal = stores.keyValue(KeyValueSpec("ai_studio_graph_attempts"))
    private val lock = Mutex()

    suspend fun run(
        chatId: String,
        request: SpawnRequest,
        previousExecution: String?,
        admission: kotlinx.coroutines.flow.Flow<Boolean>,
    ): GraphTaskResult {
        val known = lock.withLock { read() }
        val recovered = known.recoveryAttempt(chatId, previousExecution)
        val root = previousExecution?.let { known.graphWorkRoot(chatId, it) }
        cachedResult(known, chatId, root)?.let { return it }
        val records = chats.get(ChatsKey).orEmpty()
        val record = records.first { it.id == chatId }
        check((record.executionWorkspace ?: record.projectId?.let(::WorkspaceRef)) == request.workspace) {
            "The helper workspace differs from its approved graph"
        }
        val work = checkNotNull(retainGraphWork(recovered?.work, workSnapshot(chatId, request)))
        val execution = request.prompt.request.value
        val recover = root?.takeIf { record.ref != null }?.let {
            (known.recoveryLineage(chatId, it).keys + it).map(::RequestId)
        }.orEmpty()
        val checkpoint = recovered?.checkpoint
        val incoming = request.graphAttempt(chatId, checkpoint, root)
        val bound = record(execution, incoming.copy(work = work, causes = recovered?.causes ?: incoming.causes))
        log.i { "run graph assignment in a studio chat recovery=${recover.isNotEmpty()}" }
        runs.run(
            host,
            StudioTurnRequest(
                chatId,
                work.prompt,
                checkNotNull(bound.work).settings,
                WorktreeRunKind.Coding,
                request.prompt.request,
                directives = listOf(work.directive) + if (previousExecution != null) {
                    listOf(
                        "Heartbeat restarted. Continue this assignment in the existing chat. " +
                            "Check effects of interrupted work before repeating it.",

                    )
                } else {
                    emptyList()
                },
                recoveryRequests = recover,
                recoveryCheckpoint = checkpoint,
                causes = bound.causes,
                origin = StudioTurnOrigin.GraphWork(RequestId(bound.recoveryRoot ?: execution)),
                graphWork = work,
                onNativeTurn = { active, native ->
                    val inspector = (active.features.resolve(RestoresSessionTurns) as? FeatureAccess.Available)?.feature
                    val receipt = inspector?.checkpoint(native.request)
                    if (receipt != null) update(execution) { it.copy(checkpoint = receipt) }
                },
                onNativeOutcome = { outcome, evidence ->
                    val identity = evidence?.let { GraphNativeIdentity(it.session, it.request, it.turn) }
                    val result = outcome.graphResult(answer(identity))
                    update(execution) { it.withCompletion(result, identity) }
                },
            ),
            waitForIdle = true,
            cancelBeforeSubmission = true,
            admission = admission.map { if (it) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Defer },
        )
        return lock.withLock { read()[execution]?.result }
            ?: GraphTaskResult(GraphTaskPhase.RecoveryRequired, "Native task outcome is not confirmed")
    }

    private suspend fun cachedResult(
        known: Map<String, GraphChatAttempt>,
        chat: String,
        root: String?,
    ): GraphTaskResult? {
        if (root == null) return null
        val head = attempts.recoveryWork(HelperId(chat), RequestId(root))
        val result = known.cachedGraphResult(chat, root, head)
        return result?.takeUnless { it.phase == GraphTaskPhase.Cancelled && !head.isStopRequested }
    }

    private suspend fun workSnapshot(chatId: String, request: SpawnRequest): StudioGraphWorkSnapshot {
        val records = chats.get(ChatsKey).orEmpty()
        val record = records.first { it.id == chatId }
        val configurations = runtime.state.value.configurations
        val defaults = records.graphDefaults(request.parent, configurations, runtime.defaults())
        val configured = configurations[chatId]?.applied ?: record.configuration
        val effort = if (configured?.modelId == request.target.studioModelId()) {
            configured.reasoningEffort
        } else {
            (efforts.state.value as? EffortConfigurationState.Ready)?.effortFor(request.target)
        }
        return request.graphWork(hostRunSettings(record, configured, defaults, request.target))
            .copy(reasoningEffort = effort)
    }

    /** Graph Stop uses the same durable roots and native head as result recovery, without model-changing opening. */
    suspend fun stop(chatId: String): Boolean {
        val known = lock.withLock { read().filterValues { it.chat == chatId } }
        val record = chats.get(ChatsKey).orEmpty().firstOrNull { it.id == chatId } ?: return false
        return stopGraphAssignments(known, record, runs.currentSendRequest(chatId), attempts) {
            observations.cancel(record, it)
        }
    }

    private suspend fun update(execution: String, change: (GraphChatAttempt) -> GraphChatAttempt) = lock.withLock {
        val records = read()
        journal.set(ATTEMPTS, Json.encodeToString(records + (execution to change(records.getValue(execution)))))
    }

    private suspend fun record(execution: String, attempt: GraphChatAttempt): GraphChatAttempt = lock.withLock {
        val records = read()
        val bound = retainGraphAttempt(execution, records[execution], attempt)
        journal.set(ATTEMPTS, Json.encodeToString(records + (execution to bound)))
        bound
    }

    private suspend fun read(): Map<String, GraphChatAttempt> {
        val raw = journal.get(ATTEMPTS) ?: return emptyMap()
        return try {
            Json.decodeFromString(raw)
        } catch (e: IllegalArgumentException) {
            throw GraphJournalException(e::class.simpleName.orEmpty())
        }
    }

    /** Missing turn evidence yields no text, including terminal-only native inspection after a restart. */
    private suspend fun answer(native: GraphNativeIdentity?): String = try {
        if (native == null) {
            ""
        } else {
            val history = facade.sessions.get(native.session).features.requireFeature(SessionHistory)
            graphAnswer(history.page(HistoryPageRequest(limit = 20)).items, native)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "graph helper answer unavailable" }
        "Result text is unavailable; open the helper chat"
    }

    private companion object {
        val ATTEMPTS = stringKey("attempts_v1")
    }
}

/** Native failure evidence takes precedence over partial output, including when the output must be truncated. */
internal fun TurnOutcome.graphResult(output: String): GraphTaskResult = GraphTaskResult(
    when (this) {
        TurnOutcome.Completed -> GraphTaskPhase.Succeeded
        TurnOutcome.Cancelled -> GraphTaskPhase.Cancelled
        is TurnOutcome.Failed -> GraphTaskPhase.Failed
        TurnOutcome.Unknown -> GraphTaskPhase.RecoveryRequired
    },
    listOfNotNull(
        when (this) {
            TurnOutcome.Completed -> null
            TurnOutcome.Cancelled -> "Agent turn was cancelled"
            is TurnOutcome.Failed -> "Agent turn failed: ${failure.code}"
            TurnOutcome.Unknown -> "Native task outcome is not confirmed"
        },
        output.takeIf { it.isNotBlank() },
    ).joinToString("\n\n").take(SchedulerLimits.MAX_PAYLOAD),
)

/** Parser diagnostics can contain private result text; retain only the failure type. */
private class GraphJournalException(type: String) : IllegalStateException("Unreadable graph chat attempts ($type)")

/** An exact execution cannot switch chats or recovery roots; replay may only add causal restrictions. */
internal fun retainGraphAttempt(
    execution: String,
    previous: GraphChatAttempt?,
    incoming: GraphChatAttempt,
): GraphChatAttempt {
    if (previous == null) return incoming
    check(
        previous.chat == incoming.chat &&
            (previous.recoveryRoot ?: execution) == (incoming.recoveryRoot ?: execution),
    ) {
        "Graph execution identity changed"
    }
    return previous.copy(
        causes = previous.causes,
        work = retainGraphWork(previous.work, incoming.work),
    )
}

private fun SpawnRequest.graphAttempt(chat: String, checkpoint: String?, previousExecution: String?): GraphChatAttempt =
    GraphChatAttempt(
        chat,
        checkpoint = checkpoint,
        recoveryRoot = previousExecution ?: prompt.request.value,
        causes = causes + prompt.causes,
    )

/**
 * Defaults of a graph assignment: the approval of the chat that owns [parent], also after that chat continued in a new
 * native session, so a rollover never trades the parent's approval for the profile default.
 */
internal fun List<StudioChatRecord>.graphDefaults(
    parent: SessionRef,
    configurations: Map<String, StudioSessionConfiguration>,
    base: io.aequicor.heartbeat.feature.aistudio.api.RunSettings,
): io.aequicor.heartbeat.feature.aistudio.api.RunSettings {
    val owner = firstOrNull { it.owns(parent) } ?: return base
    val approval = (configurations[owner.id]?.applied ?: owner.configuration)?.approval ?: return base
    return base.copy(approval = approval)
}

/** No last-message or last-prompt fallback: those may belong to independent work in this conversation. */
internal fun graphAnswer(items: List<SessionItem>, native: GraphNativeIdentity): String =
    native.turn?.let { answerOf(items, it, SchedulerLimits.MAX_PAYLOAD, isMarkedOnly = true) }.orEmpty()

/** Loss of observation does not erase a previously recorded native address. */
internal fun GraphChatAttempt.withCompletion(result: GraphTaskResult, native: GraphNativeIdentity?): GraphChatAttempt {
    check(result.phase == GraphTaskPhase.RecoveryRequired || native != null) {
        "A isConfirmed graph result needs actual native identity"
    }
    return copy(result = result, native = native ?: this.native)
}
