package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class GraphChatAttempt(
    val chat: String,
    val result: GraphTaskResult? = null,
    val checkpoint: String? = null,
    val recoveryRoot: String? = null,
    val causes: Set<RequestInitiator> = emptySet(),
)

/** Native graph submissions and results survive a crash between studio completion and scheduler acknowledgement. */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioGraphExecutions(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val runtime: StudioRuntime,
    private val runs: StudioRunCoordinator,
    private val host: StudioRunHost,
    private val turns: StudioTurnHost,
    private val facade: EngineFacade,
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
        val previous = recovered?.result
        if (previous != null && previous.phase !in setOf(GraphTaskPhase.RecoveryRequired, GraphTaskPhase.Cancelled)) {
            return previous
        }
        val records = chats.get(ChatsKey).orEmpty()
        val record = records.first { it.id == chatId }
        check((record.executionWorkspace ?: record.projectId?.let(::WorkspaceRef)) == request.workspace) {
            "The helper workspace differs from its approved graph"
        }
        val settings = settings(chatId, request)
        val execution = request.prompt.request.value
        val recover = previousExecution?.takeIf { record.ref != null }?.let {
            (known.recoveryLineage(chatId, it).keys + it).map(::RequestId)
        }.orEmpty()
        val checkpoint = recovered?.checkpoint
        val bound = record(execution, request.graphAttempt(chatId, checkpoint, previousExecution))
        log.i { "run graph assignment in a studio chat recovery=${recover.isNotEmpty()}" }
        runs.run(
            host,
            StudioTurnRequest(
                chatId,
                request.prompt.visible,
                settings,
                WorktreeRunKind.Coding,
                request.prompt.request,
                directives = listOf(request.prompt.directive) + if (previousExecution != null) {
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
                onTurnAccepted = { active ->
                    val inspector = (active.features.resolve(RestoresSessionTurns) as? FeatureAccess.Available)?.feature
                    val receipt = inspector?.checkpoint(request.prompt.request)
                    if (receipt != null) update(execution) { it.copy(checkpoint = receipt) }
                },
                onOutcome = { outcome ->
                    val result = outcome.graphResult(answer(chatId))
                    update(execution) { it.copy(result = result) }
                },
            ),
            waitForIdle = true,
            cancelBeforeSubmission = true,
            admission = admission.map { if (it) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Defer },
        )
        return lock.withLock { read()[execution]?.result }
            ?: GraphTaskResult(GraphTaskPhase.RecoveryRequired, "Native task outcome is not confirmed")
    }

    private suspend fun settings(
        chatId: String,
        request: SpawnRequest,
    ): io.aequicor.heartbeat.feature.aistudio.api.RunSettings {
        val records = chats.get(ChatsKey).orEmpty()
        val record = records.first { it.id == chatId }
        val parent = records.firstOrNull { it.ref == request.parent }
        val parentSettings = parent?.let { runtime.state.value.configurations[it.id]?.applied ?: it.configuration }
        val base = runtime.defaults()
        val defaults = parentSettings?.let { base.copy(approval = it.approval) } ?: base
        return hostRunSettings(record, runtime.state.value.configurations[chatId]?.applied, defaults, request.target)
    }

    /** Cancellation is acknowledged only after native terminal state, including after a profile restart. */
    suspend fun stop(chatId: String): Boolean {
        val known = lock.withLock { read().filterValues { it.chat == chatId } }
        val record = chats.get(ChatsKey).orEmpty().firstOrNull { it.id == chatId } ?: return false
        if (record.ref == null) {
            runtime.cancel(chatId)
            return withTimeoutOrNull(30.seconds) {
                runtime.state.first { chatId !in it.running }
                true
            } == true
        }
        val settings = hostRunSettings(record, runtime.state.value.configurations[chatId]?.applied, runtime.defaults())
        val active = turns.openTurn(chatId, settings)
        val inspector = (active.features.resolve(RestoresSessionTurns) as? FeatureAccess.Available)?.feature
        if (inspector != null) {
            val isStopped = inspectStop(active, known, inspector.inspect(known.values.lastOrNull()?.checkpoint))
            if (isStopped != null) return isStopped
        } else if (active.state.value is ActiveSessionState.Unavailable) {
            active.features.requireFeature(ReconcilesSession).synchronize()
        }
        return stopActive(chatId, active, known.keys)
    }

    /** Null means a matched active turn was attached and still needs explicit cancellation. */
    private suspend fun inspectStop(
        active: io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession,
        known: Map<String, GraphChatAttempt>,
        inspected: TurnInspection,
    ): Boolean? = when (inspected) {
        TurnInspection.Idle -> true

        TurnInspection.Unknown -> false

        is TurnInspection.Observed -> when {
            inspected.request.value !in known -> false

            inspected.outcome != null -> true

            else -> if (withTimeoutOrNull(30.seconds) {
                    active.state.first {
                        it.activeTurn()?.request == inspected.request ||
                            it.lastCompletedTurn()?.request == inspected.request
                    }
                } == null
            ) {
                false
            } else {
                null
            }
        }
    }

    private suspend fun stopActive(
        chatId: String,
        active: io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession,
        known: Set<String>,
    ): Boolean = stopGraphTurn(active, known) { turn -> turns.requestStop(chatId, active, turn) }

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

    private suspend fun answer(chatId: String): String = try {
        val ref = chats.get(ChatsKey).orEmpty().firstOrNull { it.id == chatId }?.ref
        if (ref == null) {
            ""
        } else {
            val history = facade.sessions.get(ref).features.requireFeature(SessionHistory)
            history.page(HistoryPageRequest(limit = 20)).items.filterIsInstance<SessionItem.Message>()
                .lastOrNull { it.role == MessageRole.Assistant }?.parts?.filterIsInstance<ContentPart.Text>()
                ?.joinToString("\n") { it.text }.orEmpty().take(SchedulerLimits.MAX_PAYLOAD)
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

/**
 * Reconcile only the current recovery lineage. The scheduler retains its root across observer restarts; a new
 * explicit retry has a new root even if it crashes before journaling. A missing observer record cannot discard
 * a prior receipt, and an older retry's result cannot satisfy the new assignment. Map order is journal order.
 */
internal fun Map<String, GraphChatAttempt>.recoveryAttempt(
    chatId: String,
    previousExecution: String?,
): GraphChatAttempt? {
    if (previousExecution == null) return null
    return recoveryLineage(chatId, previousExecution).values.lastOrNull()
}

/** Only submissions of the same retry lineage may be adopted from native or cached session state. */
internal fun Map<String, GraphChatAttempt>.recoveryLineage(
    chatId: String,
    root: String,
): Map<String, GraphChatAttempt> = filter { (execution, attempt) ->
    attempt.chat == chatId && (attempt.recoveryRoot ?: execution) == root
}

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
    return previous.copy(causes = previous.causes + incoming.causes)
}

private fun SpawnRequest.graphAttempt(chat: String, checkpoint: String?, previousExecution: String?): GraphChatAttempt =
    GraphChatAttempt(
        chat,
        checkpoint = checkpoint,
        recoveryRoot = previousExecution ?: prompt.request.value,
        causes = causes,
    )
