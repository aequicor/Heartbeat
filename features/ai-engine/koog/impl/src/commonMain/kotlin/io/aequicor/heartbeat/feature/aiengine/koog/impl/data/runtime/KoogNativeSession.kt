package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogSessionRecords
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
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
 * Without leases, a running turn or an operation holding its lock it reports [onIdle] so the runtime can forget it;
 * main dispatcher only.
 */
@Suppress("LongParameterList") // A session owns separate runtime, history, snapshot, and search dependencies.
internal class KoogNativeSession(
    initial: KoogRecord,
    private val identity: RuntimeIdentity,
    private val access: KoogAccess,
    private val records: KoogSessionRecords,
    private val scope: CoroutineScope,
    private val snapshot: KoogSessionSnapshot,
    private val search: SearchEngine,
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
                            // ambiguous outcome instead of a cancellation that is not its own. This is conservative
                            // when the cancellation came before the checkpoint started.
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

    private suspend fun submit(request: PromptRequest, lease: Lease): TurnId = try {
        mutex.withLock { accept(request, lease) }
    } finally {
        // A lease closed while this call held the lock left the session in place; release it now if idle.
        releaseIfIdle()
    }

    private suspend fun accept(request: PromptRequest, lease: Lease): TurnId {
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
        var isSaved = false
        try {
            records.save(record.copy(items = history.items + user, lastTurn = turn))
            isSaved = true
        } finally {
            if (!isSaved) closeClient(client)
        }
        if (isClosed) {
            // Durable acceptance already exists and is recovered as Unknown, so a plain refusal would be false.
            closeClient(client)
            throw unknownOutcome(request)
        }
        record = record.copy(items = history.items + user, lastTurn = turn)
        history.append { SessionEvent.TurnStarted(it, turn) }
        history.append { SessionEvent.ItemUpserted(it, user) }
        publish(ActiveSessionState.Running(turn))
        val provider = requireNotNull(koogProvider(connection.source))
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runTurn(turn, client, provider, model.value)
        }
        return turn.id
    }

    private suspend fun runTurn(turn: Turn, client: KoogClient, provider: KoogProvider, model: String) {
        var outcome: TurnOutcome = TurnOutcome.Unknown
        try {
            val tools = if (supportsSearchTools(client, provider, model)) koogSearchTools else emptyList()
            generate(turn, client, provider.textModel(model, tools = tools.isNotEmpty()), tools)
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

    /**
     * Search tools are sent only while the toggle is on and the model accepts tools: cloud chat models do; a local
     * Ollama model must declare the Tools capability, otherwise the plain chat keeps working without tools.
     */

    // Ollama tool support per model id, looked up once per session.
    private val toolSupport = mutableMapOf<String, Boolean>()

    private suspend fun supportsSearchTools(client: KoogClient, provider: KoogProvider, model: String): Boolean {
        if (!access.searchToolsEnabled()) return false
        if (provider != KoogProvider.Ollama) return true
        toolSupport[model]?.let { return it }
        return try {
            (client.models().firstOrNull { it.id == model }?.capabilities?.contains(LLMCapability.Tools) == true)
                .also { toolSupport[model] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.sanitized()) { "Model capabilities unavailable; search tools disabled for this turn" }
            false
        }
    }

    private suspend fun generate(turn: Turn, client: KoogClient, model: LLModel, tools: List<ToolDescriptor>) {
        log.i { "Starting provider stream" }
        var input = initialPrompt()
        repeat(MAX_TOOL_ROUNDS) { _ ->
            val round = streamRound(turn, client, model, input, tools)
            if (round.calls.isEmpty()) return
            val results = round.calls.map { call -> recordSearchCall(turn, call) }
            input = continuePrompt(input, round.text, results)
        }
        log.w { "Search tool round limit reached ($MAX_TOOL_ROUNDS)" }
        fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }

    private fun initialPrompt(): Prompt = prompt("heartbeat") {
        val calls = record.items.filterIsInstance<SessionItem.ToolCall>().associateBy { it.call }
        record.items.forEach { item ->
            when (item) {
                is SessionItem.Message -> {
                    val text = item.parts.filterIsInstance<ContentPart.Text>()
                        .joinToString("") { it.text }
                    when (item.role) {
                        MessageRole.User -> user(text)
                        MessageRole.Assistant -> assistant(text)
                        MessageRole.System -> system(text)
                    }
                }

                is SessionItem.ToolCall -> if (item.status == ToolCallStatus.Succeeded ||
                    item.status == ToolCallStatus.Failed
                ) {
                    toolCall(item.call.value, item.name, item.arguments)
                }

                is SessionItem.ToolResult -> calls[item.call]?.let { call ->
                    val text = item.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                    toolResult(item.call.value, call.name, text, item.failure != null)
                }

                is SessionItem.Plan, is SessionItem.Notice, is SessionItem.UnsupportedItem -> Unit
            }
        }
    }

    private suspend fun streamRound(
        turn: Turn,
        client: KoogClient,
        model: LLModel,
        input: Prompt,
        tools: List<ToolDescriptor>,
    ): SearchRound {
        val info = ItemInfo(
            ItemId(Uuid.random().toString()),
            history.items.size.toLong(),
            0,
            turn.id,
        )
        val texts = mutableMapOf<Int, String>()
        val calls = mutableListOf<StreamFrame.ToolCallComplete>()
        var revision = 0L
        var isEnded = false
        client.executor.executeStreaming(input, model, tools).collect { frame ->
            val hasChanged = when (frame) {
                is StreamFrame.TextDelta -> {
                    texts[frame.index ?: 0] = texts[frame.index ?: 0].orEmpty() + frame.text
                    true
                }

                is StreamFrame.TextComplete -> {
                    texts[frame.index ?: 0] = frame.text
                    true
                }

                is StreamFrame.ToolCallComplete -> {
                    calls += frame
                    false
                }

                is StreamFrame.End -> {
                    isEnded = true
                    false
                }

                is StreamFrame.ToolCallDelta,
                is StreamFrame.ReasoningDelta,
                is StreamFrame.ReasoningComplete,
                -> false
            }
            if (hasChanged) {
                revision++
                val text = texts.keys.sorted().joinToString("") { texts.getValue(it) }
                val message = SessionItem.Message(
                    info.copy(revision = revision),
                    MessageRole.Assistant,
                    listOf(ContentPart.Text(text)),
                )
                history.append { SessionEvent.ItemUpserted(it, message) }
            }
        }
        if (!isEnded) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        return SearchRound(texts.keys.sorted().joinToString("") { texts.getValue(it) }, calls)
    }

    private suspend fun recordSearchCall(turn: Turn, call: StreamFrame.ToolCallComplete): HandledSearchCall {
        val info = ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id)
        val id = ToolCallId(call.id?.takeIf { it.isNotBlank() } ?: Uuid.random().toString())
        val started = SessionItem.ToolCall(info, id, call.name, call.content, ToolCallStatus.Running)
        history.append { SessionEvent.ItemUpserted(it, started) }
        val result = executeKoogSearch(search, call)
        val status = if (result.isFailed) ToolCallStatus.Failed else ToolCallStatus.Succeeded
        history.append { SessionEvent.ItemUpserted(it, started.copy(info = info.copy(revision = 1), status = status)) }
        val output = SessionItem.ToolResult(
            ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id),
            id,
            listOf(ContentPart.Text(result.text)),
            if (result.isFailed) EngineFailure.Unknown() else null,
        )
        history.append { SessionEvent.ItemUpserted(it, output) }
        return HandledSearchCall(id.value, call.name, call.content, result)
    }

    private fun continuePrompt(input: Prompt, text: String, calls: List<HandledSearchCall>): Prompt = prompt(
        "heartbeat",
    ) {
        messages(input.messages)
        if (text.isNotBlank()) assistant(text)
        calls.forEach { toolCall(it.id, it.name, it.arguments) }
        calls.forEach { toolResult(it.id, it.name, it.result.text, it.result.isFailed) }
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
        // A locked mutex means a submission may still become durable, so the session is not idle yet.
        if (handles.isEmpty() && current is ActiveSessionState.Ready && !mutex.isLocked) onIdle(this)
    }

    private suspend fun interrupt(turn: TurnId) {
        val interrupted = try {
            mutex.withLock { stop(turn) }
        } finally {
            // The lock may have been handed over from a failed submission whose lease is gone.
            releaseIfIdle()
        }
        interrupted?.join()
    }

    private fun stop(turn: TurnId): Job? {
        checkOpen()
        val running = current as? ActiveSessionState.Running
            ?: fail(EngineFailure.Session(SessionFailureReason.Changed))
        if (running.turn.id != turn) fail(EngineFailure.Session(SessionFailureReason.Changed))
        publish(ActiveSessionState.Interrupting(running.turn))
        return job?.also { it.cancel() }
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

/** Upper bound of search tool rounds in one turn. */
internal const val MAX_TOOL_ROUNDS = 8

private typealias Lease = MutableStateFlow<ActiveSessionState>

private data class SearchRound(val text: String, val calls: List<StreamFrame.ToolCallComplete>)

private data class HandledSearchCall(
    val id: String,
    val name: String,
    val arguments: String,
    val result: KoogSearchResult,
)

private fun unknownOutcome(request: PromptRequest) =
    EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id))
