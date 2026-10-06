package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationUpdate
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Profile-owned native session. Only its leases have screen lifetime; one mutex serializes submissions.
 * History observation belongs to the profile: a watch outlives the lease that started it.
 * Without leases, a running turn or an operation holding its lock it reports [onIdle] so the runtime can forget it;
 * main dispatcher only.
 */
@Suppress("LongParameterList") // A session owns separate runtime, history, snapshot, search and workspace sources.
internal class KoogNativeSession(
    initial: KoogRecord,
    private val identity: RuntimeIdentity,
    private val access: KoogAccess,
    private val records: KoogSessionRecords,
    private val scope: CoroutineScope,
    private val snapshot: KoogSessionSnapshot,
    private val search: SearchEngine,
    private val workspaces: KoogWorkspaces,
) {
    /** Set by the owning runtime before the first lease is issued. */
    var onIdle: (KoogNativeSession) -> Unit = {}

    /**
     * Hosted tools for a session without a project. Chosen by the lease that attaches without other holders;
     * only callers answering hosted permissions enable it.
     */
    private var areDetachedToolsEnabled = false

    // The opt-in of the running turn, captured at its acceptance; a later lease changes only later turns.
    private var isTurnDetached = false
    private val log = Log.tag("KoogSession")
    val history = snapshot.history
    private val mutex = Mutex()
    private val contextUsage = KoogSessionUsage(access, scope)
    private var record = initial
    private var job: Job? = null
    private var isClosed = false
    private val handles = mutableListOf<MutableStateFlow<ActiveSessionState>>()
    private var current: ActiveSessionState = ActiveSessionState.Ready(initial.lastTurn)
    private val configuration = MutableStateFlow(SessionConfiguration(initial.model, initial.reasoningEffort))
    private val updates = MutableSharedFlow<SessionConfigurationUpdate>(extraBufferCapacity = 16)
    private var selection = ConfigurationSelection(configuration.value)

    // The running turn with the permission ids resolved so far, and its pending approval; main dispatcher only.
    private var active: Turn? = null
    private var approval: PendingApproval? = null

    // Tools the user allowed for the rest of this native session.
    private val allowedTools = mutableSetOf<String>()
    private val toolExecution = KoogToolExecution(history, this::approve)

    val route: ExecutionRoute = requireNotNull(initial.summary.lastRoute)
    val ref: SessionRef = initial.summary.ref
    val model: ModelId get() = configuration.value.model

    /**
     * Issues a lease. Without other holders [areDetachedToolsEnabled] decides hosted tools for later turns of a
     * session without a project, even while a turn accepted for an earlier lease still runs; a lease on a session
     * that is already held keeps the holders' choice.
     */
    fun attach(areDetachedToolsEnabled: Boolean): ActiveSession {
        checkOpen()
        if (handles.isEmpty()) this.areDetachedToolsEnabled = areDetachedToolsEnabled
        val state = MutableStateFlow(current)
        handles += state
        return object : ActiveSession {
            override val ref = this@KoogNativeSession.ref
            override val route = this@KoogNativeSession.route
            override val state = state.asStateFlow()
            override val features = KoogFeatures(
                AcceptsImages to object : AcceptsImages {
                    override val mediaTypes: Set<String> get() = inputSupport().imageMediaTypes
                },
                AcceptsResources to object : AcceptsResources {
                    override val mediaTypes: Set<String> get() = inputSupport().resourceMediaTypes
                },
                AppliesTrustLevels to object : AppliesTrustLevels {},
                SessionContextUsage to contextUsage,
                ChangesSessionConfiguration to object : ChangesSessionConfiguration {
                    override val configuration = this@KoogNativeSession.configuration.asStateFlow()
                    override val updates = this@KoogNativeSession.updates.asSharedFlow()

                    override suspend fun apply(
                        operationId: String,
                        change: SessionConfigurationChange,
                    ): SessionConfiguration = withContext(scope.coroutineContext.minusKey(Job)) {
                        koogCall { configure(state, operationId, change) }
                    }
                },
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
                RequestsPermissions to object : RequestsPermissions {
                    override suspend fun respond(decision: PermissionDecision) =
                        withContext(scope.coroutineContext.minusKey(Job)) {
                            checkLease(state)
                            answer(decision)
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
                if (handles.isEmpty() && approval != null) {
                    // Nobody is left to answer; the turn would wait forever and keep the session alive.
                    log.i { "Last lease closed while a tool awaits approval; cancelling the turn" }
                    job?.cancel()
                }
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
        val origin = connection.source.scope.origin
        val effort = request.reasoningEffort ?: configuration.value.reasoningEffort
        if (effort != null && effort !in access.reasoning.levels(provider, origin, model.value)) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
        val client = access.preparePromptClient(
            connection,
            model.value,
            request,
            record.items.filterIsInstance<SessionItem.Message>().map { item ->
                if (item.role == MessageRole.User) item.parts else item.parts.filterIsInstance<ContentPart.Text>()
            },
            ::closeClient,
        )
        val turn = Turn(TurnId(Uuid.random().toString()), request.id, EngineTarget(route.engine, route.binding, model))
        val user = SessionItem.Message(
            ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id),
            MessageRole.User,
            request.parts.toList(),
        )
        // The local runtime is the native authority: acceptance occurs only after its durable checkpoint.
        var isSaved = false
        try {
            records.save(record.copy(items = history.items + user, lastTurn = turn, reasoningEffort = effort))
            isSaved = true
        } finally {
            if (!isSaved) closeClient(client)
        }
        if (isClosed) {
            // Durable acceptance already exists and is recovered as Unknown, so a plain refusal would be false.
            closeClient(client)
            throw unknownOutcome(request)
        }
        record = record.copy(items = history.items + user, lastTurn = turn, reasoningEffort = effort)
        if (effort != configuration.value.reasoningEffort) {
            install(configuration.value.copy(reasoningEffort = effort))
        }
        history.append { SessionEvent.TurnStarted(it, turn) }
        history.append { SessionEvent.ItemUpserted(it, user) }
        active = turn
        isTurnDetached = areDetachedToolsEnabled
        publish(ActiveSessionState.Running(turn))
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runTurn(turn, client, provider, origin, model.value, request.trust ?: TrustLevel.Ask)
        }
        return turn.id
    }

    /** Project tools for a project session; detached hosted tools for a chat whose caller opted in. */
    private suspend fun turnWorkspace(context: AgentToolContext): KoogWorkspace? {
        val isCodingEnabled = access.codingToolsEnabled()
        val project = route.workspace
        return when {
            project != null -> project.takeIf { workspaces.hasHostedTools || isCodingEnabled }
                ?.let { workspaces.open(it, context) }
                ?.withCodingTools(isCodingEnabled)

            isTurnDetached -> openDetached(context)

            else -> null
        }
    }

    /** Detached hosted tools are optional: a failing contribution leaves a plain chat turn. */
    private suspend fun openDetached(context: AgentToolContext): KoogWorkspace? = try {
        workspaces.openDetached(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.sanitized()) { "Detached hosted tools unavailable; the chat turn continues without them" }
        null
    }

    private suspend fun runTurn(
        turn: Turn,
        initialClient: KoogClient,
        provider: KoogProvider,
        origin: EndpointOrigin,
        initialModel: String,
        trust: TrustLevel,
    ) {
        var client = initialClient
        var clientModel = initialModel
        var outcome: TurnOutcome = TurnOutcome.Unknown
        try {
            val context = koogHostedContext(ref, route.workspace, turn, trust) { approveHosted(turn, it) }
            val workspace = turnWorkspace(context)
            val rounds = if (workspace != null && route.workspace != null) MAX_CODING_TOOL_ROUNDS else MAX_TOOL_ROUNDS
            var input: Prompt? = null
            var isComplete = false
            var remainingRounds = rounds
            while (remainingRounds-- > 0) {
                // This snapshot owns the next request and all tools returned by it. Changes while it is in
                // flight are read at the following boundary, without interrupting the stream or tool.
                val requestSelection = mutex.withLock { selection }
                val selectedModel = requestSelection.configuration.model.value
                if (selectedModel != clientModel) {
                    client = reopenClient(selectedModel, client)
                    clientModel = selectedModel
                }
                val tools = turnTools(client, provider, selectedModel, workspace, context)
                val textModel = provider.textModel(
                    selectedModel,
                    tools = !tools.isEmpty(),
                    attachments = record.items.hasResourceInputs(),
                )
                // Hosted instructions only accompany the tools they describe; blank ones add no system message.
                val instructions = workspace?.instructions?.takeIf { it.isNotBlank() && !tools.isEmpty() }
                val prompt = input ?: initialPrompt(provider, instructions)
                val round = streamWithEffort(turn, client, provider, origin, textModel, tools, prompt, requestSelection)
                val next = toolExecution.nextPrompt(turn, tools, round, prompt)
                if (next == null) {
                    isComplete = true
                    break
                }
                input = next
            }
            if (!isComplete) {
                log.w { "Tool round limit reached ($rounds)" }
                fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            }
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

    /** Reopens the transport after a live selection changed the model mid-turn. */
    private suspend fun reopenClient(model: String, client: KoogClient): KoogClient {
        val connection = access.route(route.binding, identity)
        val next = koogCall { access.open(connection, model) }
        closeClient(client)
        return next
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
     * Tools of this turn: search tools while their toggle is on, and the hosted tools of [workspace] — the project's
     * coding tools on Desktop, or detached hosted tools of a chat without a project whose caller opted in.
     * Tools are sent only when the model accepts them; without them the turn also omits their instructions.
     */
    private suspend fun turnTools(
        client: KoogClient,
        provider: KoogProvider,
        model: String,
        workspace: KoogWorkspace?,
        context: AgentToolContext,
    ): KoogToolbox {
        val offered = buildList {
            if (access.searchToolsEnabled()) addAll(koogSearchToolset(search, access.tools, context))
            workspace?.let { addAll(it.tools) }
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

    private suspend fun configure(
        lease: Lease,
        operationId: String,
        change: SessionConfigurationChange,
    ): SessionConfiguration = try {
        mutex.withLock {
            checkLease(lease)
            if (operationId.isBlank()) fail(EngineFailure.Request(RequestFailureReason.Invalid))
            val current = configuration.value
            val next = KoogConfigurationValidator(access, identity, route).resolve(current, change)
            checkLease(lease)
            access.route(route.binding, identity)
            val updated = record.copy(model = next.model, reasoningEffort = next.reasoningEffort, items = history.items)
            records.save(updated)
            checkOpen()
            record = updated
            val effortOperation = when {
                change is SessionConfigurationChange.Effort -> operationId
                next.reasoningEffort != current.reasoningEffort -> null
                else -> selection.effortOperation
            }
            install(next, effortOperation)
            log.i {
                "Installed session configuration model=${next.model.value} effort=${next.reasoningEffort ?: "default"}"
            }
            next
        }
    } finally {
        releaseIfIdle()
    }

    private fun install(next: SessionConfiguration, effortOperation: String? = null) {
        log.d { "Confirm session configuration model=${next.model.value} effort=${next.reasoningEffort ?: "default"}" }
        selection = ConfigurationSelection(next, selection.revision + 1, effortOperation)
        configuration.value = next
    }

    /**
     * A provider refusal before this round produces output is retried with exactly the same messages and tools.
     * Only a successful retry disables effort. A late rejection cannot overwrite a newer installed selection.
     */
    private suspend fun streamWithEffort(
        turn: Turn,
        client: KoogClient,
        provider: KoogProvider,
        origin: EndpointOrigin,
        model: LLModel,
        tools: KoogToolbox,
        input: Prompt,
        requestSelection: ConfigurationSelection,
    ): ToolRound {
        val effort = requestSelection.configuration.reasoningEffort
        val produced = history.items.size
        return try {
            streamRound(
                turn,
                client,
                model,
                input.withParams(provider.reasoningParams(effort, model.maxOutputTokens)),
                tools.descriptors,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (effort == null || !e.isRequestRejection() || history.items.size != produced) throw e
            log.w(e.sanitized()) { "Reasoning parameters rejected; retrying without effort" }
            val result = streamRound(turn, client, model, input.withParams(LLMParams()), tools.descriptors)
            rejectEffort(provider, origin, requestSelection)
            result
        }
    }

    private suspend fun rejectEffort(
        provider: KoogProvider,
        origin: EndpointOrigin,
        rejected: ConfigurationSelection,
    ) {
        val update = mutex.withLock {
            if (selection.revision == rejected.revision) {
                access.reasoning.reject(provider, origin, rejected.configuration.model.value)
                val next = configuration.value.copy(reasoningEffort = null)
                record = record.copy(reasoningEffort = null, items = history.items)
                install(next)
                try {
                    records.save(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e.sanitized()) { "Could not persist corrected reasoning effort" }
                }
            }
            rejected.effortOperation?.let {
                SessionConfigurationUpdate(it, configuration.value, EngineFailure.Request(RequestFailureReason.Invalid))
            }
        }
        update?.let { updates.emit(it) }
    }

    private suspend fun initialPrompt(provider: KoogProvider, instructions: String?): Prompt =
        koogHistoryPrompt(record.items, provider, inputSupport(), access.resources, instructions)

    private fun inputSupport(): io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport =
        access.inputs.cached(route.binding, model.value)

    private suspend fun streamRound(
        turn: Turn,
        client: KoogClient,
        model: LLModel,
        input: Prompt,
        tools: List<ToolDescriptor>,
    ): ToolRound {
        log.i { "Starting provider stream model=${model.id}" }
        val info = ItemInfo(
            ItemId(Uuid.random().toString()),
            history.items.size.toLong(),
            0,
            turn.id,
        )
        val content = KoogStreamParts()
        val calls = KoogToolCalls()
        var revision = 0L
        var isEnded = false
        contextUsage.start(client)
        client.executor.executeStreaming(input, model, tools).collect { frame ->
            when (frame) {
                is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete -> calls.append(frame)

                is StreamFrame.End -> {
                    isEnded = true
                    recordUsage(client, model, frame.metaInfo)
                }

                is StreamFrame.TextDelta,
                is StreamFrame.TextComplete,
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
        return ToolRound(content.text, calls.complete())
    }

    private suspend fun recordUsage(client: KoogClient, model: LLModel, metadata: ResponseMetaInfo) {
        if (!access.usageEnabled()) return
        try {
            contextUsage.complete(model.provider, model.id, access.route(route.binding, identity), client, metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.sanitized()) { "Context telemetry unavailable after provider response" }
            contextUsage.clear()
        }
    }

    /**
     * Whether a mutating call may run: automatically in auto-approve mode or for a tool the user allowed for this
     * session, otherwise after the user's decision. A target too long to show in full is refused, since approving a
     * partly shown command is not consent. Turn cancellation cancels the wait.
     *
     * @return `null` when the call may run, otherwise the refusal reason reported to the model.
     */
    private suspend fun approve(turn: Turn, tool: KoogTool, args: JsonObject): String? {
        val name = tool.descriptor.name
        if (access.autoApprove() || name in allowedTools) {
            log.i { "Tool $name approved automatically" }
            return null
        }
        val target = tool.target(args).ifBlank { name }
        if (target.length > APPROVAL_TARGET_LIMIT) {
            log.w { "Tool $name target is too long to show for approval; refusing it" }
            return "Refused: the call is longer than $APPROVAL_TARGET_LIMIT characters and cannot be shown for approval"
        }
        val request = PermissionRequest(
            PermissionRequestId(Uuid.random().toString()),
            turn.id,
            target,
            listOf(
                PermissionOption(AllowOnce, "Разрешить"),
                PermissionOption(AllowForSession, "Разрешить до конца сессии"),
                PermissionOption(Deny, "Запретить"),
            ),
            description = tool.details(args),
        )
        val pending = PendingApproval(request, CompletableDeferred())
        approval = pending
        val running = active ?: turn
        publish(ActiveSessionState.AwaitingUserAction(running, listOf(request)))
        log.i { "Tool $name awaits user approval" }
        val option = try {
            pending.answer.await()
        } finally {
            approval = null
        }
        val resolved = running.copy(resolvedPermissions = running.resolvedPermissions + request.id)
        active = resolved
        if (current is ActiveSessionState.AwaitingUserAction) publish(ActiveSessionState.Running(resolved))
        if (option == AllowForSession) allowedTools += name
        log.i { "Tool $name ${if (option == Deny) "denied" else "allowed"} by user" }
        return if (option == Deny) "Denied by the user" else null
    }

    private fun answer(decision: PermissionDecision) {
        val pending = approval
        if (pending == null || !pending.request.accepts(decision) || pending.answer.isCompleted) {
            fail(EngineFailure.Session(SessionFailureReason.Changed))
        }
        pending.answer.complete(decision.option)
    }

    /** Hosted tools use only the shared TrustLevel gate, independent of the legacy auto-approve setting. */
    private suspend fun approveHosted(turn: Turn, action: AgentToolApproval): Boolean {
        if (active?.id != turn.id || handles.isEmpty() || current is ActiveSessionState.Interrupting) return false
        val request = PermissionRequest(
            PermissionRequestId(Uuid.random().toString()),
            turn.id,
            action.title,
            listOf(PermissionOption(AllowOnce, "Разрешить"), PermissionOption(Deny, "Запретить")),
            description = action.description,
        )
        val pending = PendingApproval(request, CompletableDeferred())
        approval = pending
        val running = active ?: turn
        publish(ActiveSessionState.AwaitingUserAction(running, listOf(request)))
        history.append { SessionEvent.PermissionRequested(it, request) }
        return try {
            pending.answer.await() == AllowOnce
        } finally {
            approval = null
            val resolved = running.copy(resolvedPermissions = running.resolvedPermissions + request.id)
            active = resolved
            if (current is ActiveSessionState.AwaitingUserAction) publish(ActiveSessionState.Running(resolved))
        }
    }

    private suspend fun finish(turn: Turn, outcome: TurnOutcome) {
        mutex.withLock { persistFinished(turn, outcome) }
        releaseIfIdle()
    }

    private suspend fun persistFinished(turn: Turn, outcome: TurnOutcome) {
        log.i { "Finishing accepted turn" }
        // The running turn carries the permissions resolved during it.
        val finished = (active ?: turn).copy(outcome = outcome)
        active = null
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
        }
    }

    private fun releaseIfIdle() {
        // A locked mutex means a submission may still become durable, so the session is not idle yet.
        if (handles.isEmpty() && current is ActiveSessionState.Ready && !mutex.isLocked) {
            contextUsage.close()
            onIdle(this)
        }
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
        val running = when (val state = current) {
            is ActiveSessionState.Running -> state.turn
            is ActiveSessionState.AwaitingUserAction -> state.turn
            else -> fail(EngineFailure.Session(SessionFailureReason.Changed))
        }
        if (running.id != turn) fail(EngineFailure.Session(SessionFailureReason.Changed))
        publish(ActiveSessionState.Interrupting(running))
        return job?.also { it.cancel() }
    }

    fun dispose() {
        isClosed = true
        contextUsage.close()
        job?.cancel()
        publish(ActiveSessionState.Closed)
    }

    suspend fun shutdown() {
        mutex.withLock {
            isClosed = true
            contextUsage.close()
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

/** Longest approval target shown in full; longer mutating calls are refused. */
internal const val APPROVAL_TARGET_LIMIT = 4000

internal val AllowOnce = PermissionOptionId("allow")
internal val AllowForSession = PermissionOptionId("allow_session")
internal val Deny = PermissionOptionId("deny")

private data class PendingApproval(val request: PermissionRequest, val answer: CompletableDeferred<PermissionOptionId>)

/** A monotonic selection revision keeps late provider corrections from replacing a newer choice. */
private data class ConfigurationSelection(
    val configuration: SessionConfiguration,
    val revision: Long = 0,
    val effortOperation: String? = null,
)

private fun Prompt.withParams(params: LLMParams): Prompt = prompt(
    "heartbeat",
    params,
) { messages(this@withParams.messages) }

/** Upper bound of tool rounds in a chat turn with search tools only. */
internal const val MAX_TOOL_ROUNDS = 8

/** Upper bound of tool rounds in a coding turn; coding tasks read and edit many files. */
internal const val MAX_CODING_TOOL_ROUNDS = 64

private typealias Lease = MutableStateFlow<ActiveSessionState>

internal data class ToolRound(val text: String, val calls: List<StreamFrame.ToolCallComplete>)

private fun unknownOutcome(request: PromptRequest) =
    EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id))
