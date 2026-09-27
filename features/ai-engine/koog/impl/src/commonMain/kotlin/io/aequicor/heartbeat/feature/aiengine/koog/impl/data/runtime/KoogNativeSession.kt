package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogSessionRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Profile-owned native session. Only its leases have screen lifetime; one mutex serializes submissions.
 * History observation belongs to the profile: a watch outlives the lease that started it.
 * Without leases and a running turn it reports [onIdle] so the runtime can forget it; main dispatcher only.
 */
internal class KoogNativeSession(
    initial: KoogRecord,
    private val identity: RuntimeIdentity,
    private val access: KoogAccess,
    private val records: KoogSessionRecords,
    private val scope: CoroutineScope,
    private val snapshot: KoogSessionSnapshot,
) {
    /** Set by the owning runtime before the first lease is issued. */
    var onIdle: (KoogNativeSession) -> Unit = {}
    private val log = Log.tag("KoogSession")
    val history = snapshot.history
    private val mutex = Mutex()
    private var record = initial
    private var job: Job? = null
    private var isClosed = false
    private val handles = mutableListOf<MutableStateFlow<ActiveSessionState>>()
    private var current: ActiveSessionState = ActiveSessionState.Ready(initial.lastTurn)

    val route: ExecutionRoute = requireNotNull(initial.summary.lastRoute)
    val ref: SessionRef = initial.summary.ref
    val model: ModelId = initial.model

    fun attach(): ActiveSession {
        checkOpen()
        val state = MutableStateFlow(current)
        handles += state
        return object : ActiveSession {
            override val ref = this@KoogNativeSession.ref
            override val route = this@KoogNativeSession.route
            override val state = state.asStateFlow()
            override val features = KoogFeatures(
                SendsPrompts to object : SendsPrompts {
                    override suspend fun send(request: PromptRequest): TurnId = withContext(
                        scope.coroutineContext.minusKey(Job),
                    ) {
                        checkLease(state)
                        val accepted = CompletableDeferred<Result<TurnId>>()
                        scope.launch {
                            accepted.complete(koogResult { submit(request, state) })
                        }.invokeOnCompletion { cause ->
                            // The profile's cancellation may follow a durable acceptance: the caller learns an
                            // ambiguous outcome instead of a cancellation that is not its own.
                            if (cause != null && accepted.complete(Result.failure(unknownOutcome(request)))) {
                                log.w { "Profile closed while accepting a prompt" }
                            }
                        }
                        accepted.await().getOrThrow()
                    }
                },
                CancelsTurns to object : CancelsTurns {
                    override suspend fun cancel(turn: TurnId) = withContext(scope.coroutineContext.minusKey(Job)) {
                        checkLease(state)
                        interrupt(turn)
                    }
                },
                SessionHistory to object : SessionHistory {
                    override suspend fun page(request: HistoryPageRequest): HistoryPage =
                        withContext(scope.coroutineContext.minusKey(Job)) {
                            checkLease(state)
                            history.page(request)
                        }

                    override fun watch(after: HistoryCheckpoint) = flow {
                        checkLease(state)
                        emitAll(history.watch(after))
                    }.flowOn(scope.coroutineContext.minusKey(Job))
                },
            )

            override suspend fun close() = withContext(scope.coroutineContext.minusKey(Job)) {
                log.i { "Releasing session lease" }
                handles.remove(state)
                state.value = ActiveSessionState.Closed
                releaseIfIdle()
            }
        }
    }

    private suspend fun submit(request: PromptRequest, lease: Lease): TurnId = mutex.withLock {
        // Re-checked under the lock: the lease may have been released while this call waited for it.
        checkLease(lease)
        if (current !is ActiveSessionState.Ready) fail(EngineFailure.Session(SessionFailureReason.Busy))
        if (request.parts.any { it !is ContentPart.Text }) {
            fail(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
        }
        if (record.lastTurn?.request == request.id) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
        val connection = access.route(route.binding, identity)
        val client = koogCall { access.open(connection, model.value) }
        val turn = Turn(TurnId(Uuid.random().toString()), request.id, EngineTarget(route.engine, route.binding, model))
        val user = SessionItem.Message(
            ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id),
            MessageRole.User,
            request.parts.toList(),
        )
        // The local runtime is the native authority: acceptance occurs only after its durable checkpoint.
        try {
            records.save(record.copy(items = history.items + user, lastTurn = turn))
        } catch (e: CancellationException) {
            client.close()
            throw e
        } catch (e: Exception) {
            client.close()
            throw e
        }
        if (isClosed) {
            // Durable acceptance already exists and is recovered as Unknown, so a plain refusal would be false.
            client.close()
            throw unknownOutcome(request)
        }
        record = record.copy(items = history.items + user, lastTurn = turn)
        history.append { SessionEvent.TurnStarted(it, turn) }
        history.append { SessionEvent.ItemUpserted(it, user) }
        publish(ActiveSessionState.Running(turn))
        val provider = requireNotNull(koogProvider(connection.source))
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runTurn(turn, client, provider.textModel(model.value))
        }
        turn.id
    }

    private suspend fun runTurn(turn: Turn, client: KoogClient, model: LLModel) {
        var outcome: TurnOutcome = TurnOutcome.Unknown
        try {
            generate(turn, client, model)
            outcome = TurnOutcome.Completed
        } catch (e: CancellationException) {
            // Closing a HTTP stream confirms local termination, not remote cancellation.
            throw e
        } catch (e: Exception) {
            val safe = e.sanitized()
            log.w(safe) { "Generation failed: ${e::class.simpleName.orEmpty()}" }
            outcome = TurnOutcome.Failed(safe.failure)
        } finally {
            try {
                closeClient(client)
            } finally {
                withContext(NonCancellable) { finish(turn, outcome) }
            }
        }
    }

    private fun closeClient(client: KoogClient) {
        try {
            client.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.sanitized()) { "Could not close provider transport" }
        }
    }

    private suspend fun generate(turn: Turn, client: KoogClient, model: LLModel) {
        log.i { "Starting provider stream" }
        val input = prompt("heartbeat") {
            record.items.filterIsInstance<SessionItem.Message>().forEach { message ->
                val text = message.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                when (message.role) {
                    MessageRole.User -> user(text)
                    MessageRole.Assistant -> assistant(text)
                    MessageRole.System -> system(text)
                }
            }
        }
        val item = ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id)
        val texts = mutableMapOf<Int, String>()
        var revision = 0L
        var hasEnded = false
        client.executor.executeStreaming(input, model).collect { frame ->
            val hasChanged = when (frame) {
                is StreamFrame.TextDelta -> {
                    val index = frame.index ?: 0
                    texts[index] = texts[index].orEmpty() + frame.text
                    true
                }

                is StreamFrame.TextComplete -> {
                    texts[frame.index ?: 0] = frame.text
                    true
                }

                is StreamFrame.End -> {
                    hasEnded = true
                    false
                }

                is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete ->
                    fail(EngineFailure.Request(RequestFailureReason.UnsupportedContent, turn.request))

                is StreamFrame.ReasoningDelta, is StreamFrame.ReasoningComplete -> false
            }
            if (hasChanged) {
                revision++
                val message = SessionItem.Message(
                    item.copy(revision = revision),
                    MessageRole.Assistant,
                    listOf(ContentPart.Text(texts.keys.sorted().joinToString("") { texts.getValue(it) })),
                )
                history.append { SessionEvent.ItemUpserted(it, message) }
            }
        }
        if (!hasEnded) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }

    private suspend fun finish(turn: Turn, outcome: TurnOutcome) {
        log.i { "Finishing accepted turn" }
        val finished = turn.copy(outcome = outcome)
        if (outcome != TurnOutcome.Completed) {
            history.coverage = HistoryCoverage.Partial
        }
        record = record.copy(
            items = history.items,
            lastTurn = finished,
            coverage = history.coverage,
            summary = record.summary.copy(times = record.summary.times.copy(updatedAt = Clock.System.now())),
        )
        snapshot.update(record.summary)
        try {
            records.save(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.sanitized()) { "Could not persist terminal checkpoint" }
        } finally {
            history.append { SessionEvent.TurnFinished(it, turn.id, outcome) }
            if (!isClosed) publish(ActiveSessionState.Ready(finished))
            releaseIfIdle()
        }
    }

    private fun releaseIfIdle() {
        if (handles.isEmpty() && current is ActiveSessionState.Ready) onIdle(this)
    }

    private suspend fun interrupt(turn: TurnId) {
        val interrupted = mutex.withLock {
            checkOpen()
            val running = current as? ActiveSessionState.Running
                ?: fail(EngineFailure.Session(SessionFailureReason.Changed))
            if (running.turn.id != turn) fail(EngineFailure.Session(SessionFailureReason.Changed))
            publish(ActiveSessionState.Interrupting(running.turn))
            job?.also { it.cancel() }
        }
        interrupted?.join()
    }

    fun dispose() {
        isClosed = true
        job?.cancel()
        publish(ActiveSessionState.Closed)
    }

    suspend fun shutdown() {
        mutex.withLock {
            isClosed = true
            job?.cancel()
            publish(ActiveSessionState.Closed)
        }
        job?.join()
    }

    private fun checkOpen() {
        if (isClosed) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    private fun checkLease(state: Lease) {
        if (state.value == ActiveSessionState.Closed) {
            fail(
                EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed),
            )
        }
        checkOpen()
    }

    private fun publish(next: ActiveSessionState) {
        log.d { "Session state changed to ${next::class.simpleName.orEmpty()}" }
        current = next
        handles.forEach { it.value = next }
    }
}

private typealias Lease = MutableStateFlow<ActiveSessionState>

private fun unknownOutcome(request: PromptRequest) =
    EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id))
