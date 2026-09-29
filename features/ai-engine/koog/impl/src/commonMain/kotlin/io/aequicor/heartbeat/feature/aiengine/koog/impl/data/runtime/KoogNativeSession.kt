package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
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
        if (record.lastTurn?.request == request.id) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
        val connection = access.route(route.binding, identity)
        val provider = requireNotNull(koogProvider(connection.source))
        val effort = request.reasoningEffort
        if (effort != null && effort !in access.reasoning.levels(provider, model.value)) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
        request.parts.koogUserParts(provider, request.id)
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
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runTurn(turn, client, provider, model.value, effort)
        }
        return turn.id
    }

    private suspend fun runTurn(
        turn: Turn,
        client: KoogClient,
        provider: KoogProvider,
        model: String,
        effort: String?,
    ) {
        var outcome: TurnOutcome = TurnOutcome.Unknown
        try {
            val tools = turnTools(client, provider, model)
            val hasAttachments = record.items.hasResourceInputs()
            log.i { "Koog turn effort=${effort ?: "default"} tools=${tools.descriptors.size}" }
            val textModel = provider.textModel(model, tools = !tools.isEmpty(), attachments = hasAttachments)
            generateWithEffort(turn, client, provider, textModel, tools, effort)
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

    // Ollama tool support per model id, looked up once per session.
    private val toolSupport = mutableMapOf<String, Boolean>()

    /**
     * Tools of this turn: search tools while their toggle is on. Tools are sent only when the model accepts them.
     */
    private suspend fun turnTools(client: KoogClient, provider: KoogProvider, model: String): KoogToolbox {
        val offered = buildList {
            if (access.searchToolsEnabled()) addAll(koogSearchToolset(search))
        }
        if (offered.isEmpty() || !acceptsTools(client, provider, model)) return KoogToolbox(emptyList())
        return KoogToolbox(offered)
    }

    /**
     * Cloud chat models accept tools; a local Ollama model must declare the Tools capability, otherwise the plain
     * chat keeps working without tools.
     */
    private suspend fun acceptsTools(client: KoogClient, provider: KoogProvider, model: String): Boolean {
        if (provider != KoogProvider.Ollama) return true
        toolSupport[model]?.let { return it }
        return try {
            (client.models().firstOrNull { it.id == model }?.capabilities?.contains(LLMCapability.Tools) == true)
                .also { toolSupport[model] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.sanitized()) { "Model capabilities unavailable; tools disabled for this turn" }
            false
        }
    }

    /**
     * A provider that refuses the reasoning parameters before producing any output gets the same prompt again without
     * them. Only when that retry succeeds were the reasoning parameters the cause, and the model stops offering effort,
     * so a wrong capability guess never fails the user's turn and an unrelated 400 does not disable effort.
     */
    private suspend fun generateWithEffort(
        turn: Turn,
        client: KoogClient,
        provider: KoogProvider,
        model: LLModel,
        tools: KoogToolbox,
        effort: String?,
    ) {
        val produced = history.items.size
        try {
            generate(turn, client, model, tools, provider.reasoningParams(effort, model.maxOutputTokens))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (effort == null || !e.isRequestRejection() || history.items.size != produced) throw e
            log.w(e.sanitized()) { "Reasoning parameters rejected; retrying without effort" }
            generate(turn, client, model, tools, LLMParams())
            access.reasoning.reject(provider, model.id)
        }
    }

    private suspend fun generate(
        turn: Turn,
        client: KoogClient,
        model: LLModel,
        tools: KoogToolbox,
        params: LLMParams,
    ) {
        log.i { "Starting provider stream" }
        var input = initialPrompt(model.provider, params)
        repeat(MAX_TOOL_ROUNDS) { _ ->
            val round = streamRound(turn, client, model, input, tools.descriptors)
            if (round.calls.isEmpty()) return
            val results = round.calls.map { call -> runToolCall(turn, tools, call) }
            input = continuePrompt(input, round.text, results)
        }
        log.w { "Tool round limit reached ($MAX_TOOL_ROUNDS)" }
        fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }

    private fun initialPrompt(provider: ai.koog.prompt.llm.LLMProvider, params: LLMParams): Prompt = prompt(
        "heartbeat",
        params,
    ) {
        val koogProvider = KoogProvider.entries.first { it.llmProvider == provider }
        if (record.items.hasSourceMaterial()) system(KOOG_RESOURCE_BOUNDARY)
        val calls = record.items.filterIsInstance<SessionItem.ToolCall>().associateBy { it.call }
        record.items.forEach { item ->
            when (item) {
                is SessionItem.Message -> {
                    val text = item.parts.filterIsInstance<ContentPart.Text>()
                        .joinToString("") { it.text }
                    when (item.role) {
                        MessageRole.User -> user(item.parts.koogUserParts(koogProvider))
                        MessageRole.Assistant -> assistant(text)
                        MessageRole.System -> system(text)
                    }
                }

                is SessionItem.ToolCall -> if (item.status == ToolCallStatus.Succeeded ||
                    item.status == ToolCallStatus.Failed
                ) {
                    toolCall(tool = item.name, args = item.arguments, id = item.call.value)
                }

                is SessionItem.ToolResult -> calls[item.call]?.let { call ->
                    val text = item.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                    toolResult(tool = call.name, output = text, id = item.call.value, isError = item.failure != null)
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
    ): ToolRound {
        val info = ItemInfo(
            ItemId(Uuid.random().toString()),
            history.items.size.toLong(),
            0,
            turn.id,
        )
        val content = KoogStreamParts()
        val calls = mutableListOf<StreamFrame.ToolCallComplete>()
        var revision = 0L
        var isEnded = false
        client.executor.executeStreaming(input, model, tools).collect { frame ->
            when (frame) {
                is StreamFrame.ToolCallComplete -> {
                    calls += frame
                }

                is StreamFrame.End -> {
                    isEnded = true
                }

                is StreamFrame.TextDelta,
                is StreamFrame.TextComplete,
                is StreamFrame.ToolCallDelta,
                is StreamFrame.ReasoningDelta,
                is StreamFrame.ReasoningComplete,
                -> Unit
            }
            if (content.append(frame)) {
                revision++
                val message = SessionItem.Message(
                    info.copy(revision = revision),
                    MessageRole.Assistant,
                    content.parts,
                )
                history.append { SessionEvent.ItemUpserted(it, message) }
            }
        }
        if (!isEnded) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        return ToolRound(content.text, calls)
    }

    private suspend fun runToolCall(
        turn: Turn,
        tools: KoogToolbox,
        call: StreamFrame.ToolCallComplete,
    ): HandledToolCall {
        val info = ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id)
        val id = ToolCallId(call.id?.takeIf { it.isNotBlank() } ?: Uuid.random().toString())
        val started = SessionItem.ToolCall(info, id, call.name, call.content, ToolCallStatus.Running)
        history.append { SessionEvent.ItemUpserted(it, started) }
        val result = execute(tools[call.name], call)
        val status = if (result.isFailed) ToolCallStatus.Failed else ToolCallStatus.Succeeded
        history.append { SessionEvent.ItemUpserted(it, started.copy(info = info.copy(revision = 1), status = status)) }
        val output = SessionItem.ToolResult(
            ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id),
            id,
            listOf(ContentPart.Text(result.text)) + result.resources.map { ContentPart.Resource(it) },
            if (result.isFailed) EngineFailure.Unknown() else null,
        )
        history.append { SessionEvent.ItemUpserted(it, output) }
        return HandledToolCall(id.value, call.name, call.content, result)
    }

    private suspend fun execute(tool: KoogTool?, call: StreamFrame.ToolCallComplete): KoogToolResult {
        if (tool == null) {
            log.w { "Model called an unknown tool" }
            return KoogToolResult("Unknown tool: ${call.name}", true)
        }
        val args = try {
            call.contentJson
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Malformed tool arguments for ${call.name}" }
            return KoogToolResult("InvalidInput: arguments must be a JSON object", true)
        }
        log.i { "Running tool ${call.name}" }
        return tool.run(args)
    }

    private fun continuePrompt(input: Prompt, text: String, calls: List<HandledToolCall>): Prompt = prompt(
        "heartbeat",
        input.params,
    ) {
        if (input.messages.none { it is Message.System && it.textContent() == KOOG_RESOURCE_BOUNDARY }) {
            system(KOOG_RESOURCE_BOUNDARY)
        }
        messages(input.messages)
        if (text.isNotBlank()) assistant(text)
        calls.forEach { toolCall(tool = it.name, args = it.arguments, id = it.id) }
        calls.forEach { toolResult(tool = it.name, output = it.result.text, id = it.id, isError = it.result.isFailed) }
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

/** Upper bound of tool rounds in one turn; coding tasks read and edit many files. */
internal const val MAX_TOOL_ROUNDS = 64

private typealias Lease = MutableStateFlow<ActiveSessionState>

private data class ToolRound(val text: String, val calls: List<StreamFrame.ToolCallComplete>)

private data class HandledToolCall(val id: String, val name: String, val arguments: String, val result: KoogToolResult)

private fun unknownOutcome(request: PromptRequest) =
    EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id))
