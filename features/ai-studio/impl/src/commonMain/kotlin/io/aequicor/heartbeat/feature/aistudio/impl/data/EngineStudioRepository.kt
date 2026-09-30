package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.effectiveEffort
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeActionRequest
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ChatSpec = KeyValueSpec("ai_studio_chats")
private val ChatsKey = jsonKey("chats", ListSerializer(StudioChatRecord.serializer()))

/**
 * Persistent identity and projection; no credential material or opaque history cursors are stored.
 *
 * [items] is the carrier a version before [StudioTranscripts] wrote the transcript into: it is read once to move
 * the items into the feature database and is written empty from then on.
 */
@Serializable
internal data class StudioChatRecord(
    val id: String,
    val title: String,
    val updatedAt: Instant,
    val ref: SessionRef? = null,
    val target: EngineTarget? = null,
    val items: List<SessionItem> = emptyList(),
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val isArchived: Boolean = false,
    val hasFailed: Boolean = false,
    val failureKind: RunFailureKind = RunFailureKind.Unknown,
    val projectId: String? = null,
    /** Managed execution identity, distinct from the project's sidebar grouping. */
    val executionWorkspace: WorkspaceRef? = null,
    val worktreeTaskId: String? = null,
    val configuration: StudioSessionSettings? = null,
)

/** The profile owns accepted turns, handles and transcript projection; screens only observe. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioRepository>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioRuntime>())
@Inject
internal class EngineStudioRepository(
    private val facade: EngineFacade,
    private val selections: ModelSelections,
    private val sources: AuthSources,
    @ForScope(ProfileScope::class) stores: DataStores,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val clock: Clock,
    private val workspaces: LocalWorkspaces,
    private val efforts: EffortChoicesView,
    private val usage: EngineStudioUsage,
    private val worktrees: StudioWorktrees,
    private val turns: StudioTurnExecutor,
    private val runs: StudioRunCoordinator,
    workspaceProjection: StudioWorkspaceProjection,
    private val configurations: StudioConfigurationController,
) : StudioRepository,
    StudioRuntime,
    StudioTurnHost,
    StudioRunHost,
    StudioConfigurationAccess {
    private val log = Log.tag("EngineStudio")
    private val nativeSession = StudioNativeSessionOperations()
    private val store = stores.keyValue(ChatSpec)
    private val lock = Mutex()
    private val deliveringActions = mutableSetOf<String>()

    /**
     * Items live in the feature database; a conversation stored by an earlier version inside its record is moved
     * there on the first read. Both callbacks run under [lock], so a move never races a streamed write.
     */
    private val transcripts = StudioTranscripts(
        stores.database(StudioTranscriptDatabaseSpec).transcripts(),
        legacy = { id -> record(id).items },
        contracted = { id -> updateRecord(id) { copy(items = emptyList()) } },
    )
    private val historyMirror = StudioHistoryMirror(
        read = { id -> items(id) },
        update = { id, change -> write(id, change) },
    )

    /** Guards [handles], [stopRequests] and [opening]; never held across native or storage calls. */
    private val handlesLock = Mutex()
    private val handles = mutableMapOf<String, ActiveSession>()
    private val stopRequests = mutableSetOf<String>()

    /**
     * One lock per conversation so a native session is created, resumed or released at most once at a time.
     * An entry lives only while someone holds or awaits it.
     */
    private val opening = mutableMapOf<String, ChatLock>()
    private val mutableState = MutableStateFlow(StudioRuntimeState())
    override val state: StateFlow<StudioRuntimeState> = mutableState.asStateFlow()

    init {
        profile.coroutineScope.launch {
            worktrees.tasks().collect { tasks ->
                tasks.values.forEach { task ->
                    val action = task.actionRequest ?: return@forEach
                    val isDeliveryRequired = lock.withLock { deliveringActions.add(action.operation) }
                    if (isDeliveryRequired) profile.coroutineScope.launch { deliverAction(task.chatId, action) }
                }
            }
        }
        profile.coroutineScope.launch {
            usage.state.collect { snapshot ->
                log.d { "Update studio usage snapshot" }
                mutableState.update { it.copy(contexts = snapshot.contexts, providerUsage = snapshot.providers) }
            }
        }
        profile.coroutineScope.launch {
            store.observe(ChatsKey).collect { records ->
                log.d { "Restore confirmed configurations count=${records.orEmpty().size}" }
                mutableState.update { runtime ->
                    val restored = records.orEmpty().mapNotNull { record ->
                        val saved = record.configuration
                        saved?.let { record.id to StudioSessionConfiguration(it) }
                    }.toMap()
                    runtime.copy(configurations = restored + runtime.configurations)
                }
            }
        }
    }

    override suspend fun observeUsageTargets(modelIds: Set<String>) {
        log.d { "Observe composer usage routes" }
        usage.observe(modelIds)
    }

    override suspend fun refreshUsage(modelId: String) {
        log.d { "Refresh composer usage" }
        usage.refresh(modelId)
    }

    private val offeredModels = facade.observeStudioModels(selections, sources)
        .stateIn(profile.coroutineScope, SharingStarted.WhileSubscribed(), emptyList())

    /** All subscribers share the same sidebar projection and native-resume probes. */
    private val workspaceFlow = workspaceProjection.observe(
        store.observe(ChatsKey),
        profile.coroutineScope,
        state.map { it.running },
    ) { id -> handlesLock.withLock { handles[id] } }
    override fun observeWorkspace(): Flow<StudioWorkspace> {
        log.d { "observeWorkspace" }
        return workspaceFlow
    }

    override fun observeMessages(sessionId: String): Flow<List<StudioMessage>> {
        log.d { "observeMessages" }
        return flow {
            // A conversation stored by an earlier version becomes readable from the database before observation.
            items(sessionId)
            emitAll(
                combine(
                    transcripts.observe(sessionId),
                    state,
                    projection(sessionId),
                    configurations.feedback(sessionId),
                ) { stored, runtime, record, feedback ->
                    stored.toStudioMessages(record.updatedAt, sessionId in runtime.running, feedback) +
                        if (record.hasFailed) {
                            listOf(StudioMessage.Failed("failure", record.updatedAt, record.failureKind))
                        } else {
                            emptyList()
                        }
                },
            )
        }
    }

    /**
     * What the transcript of [id] is projected with: the items live in the database, while the time and the failure
     * of the conversation stay in its record. Only their change re-projects, never a stored item.
     */
    private fun projection(id: String): Flow<StudioChatRecord> = store.observe(ChatsKey)
        .map { records ->
            records.orEmpty().firstOrNull { it.id == id } ?: StudioChatRecord(id, "", Instant.DISTANT_PAST)
        }
        .distinctUntilChanged()

    override fun observeModels(): Flow<List<StudioModel>> {
        log.d { "Observe enabled models and their cached capabilities" }
        return offeredModels
    }

    override suspend fun defaults(): RunSettings {
        log.d { "Read model defaults" }
        val selection = selections.observe().first()
        val target = selection.defaultTarget
        return DefaultRunSettings.copy(
            modelId = target?.let { Json.encodeToString(EngineTarget.serializer(), it) }.orEmpty(),
        )
    }

    override suspend fun defaultProjectId(): String? {
        log.d { "Read default workspace" }
        return null
    }

    override suspend fun createSession(projectId: String?, title: String): StudioSession {
        log.i { "Create conversation in the selected project" }
        return createSession(projectId, title, isWorktree = false)
    }

    override suspend fun createSession(projectId: String?, title: String, isWorktree: Boolean): StudioSession {
        log.i { "Create studio conversation worktree=$isWorktree" }
        if (projectId != null) {
            requireNotNull(workspaces.resolve(WorkspaceRef(projectId))) { "The project folder is unavailable" }
        }
        val id = Uuid.random().toString()
        val isolated = if (isWorktree) {
            val project = WorkspaceRef(requireNotNull(projectId) { "Worktree requires a local project" })
            worktrees.send(WorktreeIntent.Public.Prepare(id, project))
            worktrees.await(id) { it.executionWorkspace != null || it.phase == WorktreePhase.Failed }.also {
                check(it.executionWorkspace != null && it.phase != WorktreePhase.Failed) {
                    "Worktree preparation failed: ${it.failure.orEmpty()}"
                }
            }
        } else {
            null
        }
        val record = StudioChatRecord(
            id,
            title,
            clock.now(),
            projectId = projectId,
            executionWorkspace = isolated?.executionWorkspace,
            worktreeTaskId = isolated?.chatId,
        )
        lock.withLock { store.set(ChatsKey, store.get(ChatsKey).orEmpty() + record) }
        log.i { "Created studio conversation" }
        return StudioSession(record.id, record.projectId, title, record.updatedAt)
    }

    /** Mark delivery durably before invoking native code; recovery never automatically sends the action again. */
    private suspend fun deliverAction(id: String, action: WorktreeActionRequest) {
        try {
            state.first { id !in it.running }
            val stored = record(id)
            val confirmed = state.value.configurations[id]?.applied ?: stored.configuration
            val defaults = defaults()
            val settings = defaults.copy(
                modelId = confirmed?.modelId ?: stored.target?.studioModelId().orEmpty(),
                approval = confirmed?.approval ?: defaults.approval,
            )
            launchRun(id, action.prompt, settings, action.kind, RequestId(action.operation), waitForIdle = true) {
                worktrees.send(WorktreeIntent.Public.ActionDelivered(id, action.operation))
                worktrees.await(id, failOnTaskError = false) {
                    it.actionRequest == null && it.phase == WorktreePhase.ActionWorking &&
                        it.expectedAction?.operation == action.operation
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Worktree action delivery failed; preserve the journal for explicit recovery" }
            try {
                worktrees.send(WorktreeIntent.Public.ActionDeliveryFailed(id, action.operation, "ActionDeliveryFailed"))
            } catch (deliveryError: CancellationException) {
                throw deliveryError
            } catch (deliveryError: Exception) {
                log.e(deliveryError) { "Could not persist the failed action handoff" }
            }
            update(id) { copy(hasFailed = true) }
        }
    }

    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome {
        log.i { "Run studio conversation" }
        return launchRun(sessionId, prompt, settings, WorktreeRunKind.Coding, RequestId(Uuid.random().toString()))
    }

    private suspend fun launchRun(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        kind: WorktreeRunKind,
        request: RequestId,
        waitForIdle: Boolean = false,
        beforeExecute: suspend () -> Unit = {},
    ): RunOutcome = runs.run(
        this,
        StudioTurnRequest(sessionId, prompt, settings, kind, request),
        waitForIdle,
        beforeExecute,
    )

    override suspend fun startedRun(id: String, at: Instant) {
        log.i { "Start profile-owned execution" }
        mutableState.update { it.copy(running = it.running + id, runStartedAt = it.runStartedAt + (id to at)) }
    }

    override suspend fun executeRun(request: StudioTurnRequest): RunOutcome {
        log.i { "Execute the reserved native request" }
        val result = turns.execute(this, request)
        releaseArchived(request.id)
        return result
    }

    override suspend fun finishedRun(id: String) {
        log.i { "Profile execution ended; clear local run state" }
        handlesLock.withLock {
            stopRequests.remove(id)
            mutableState.update {
                it.copy(
                    running = it.running - id,
                    runStartedAt = it.runStartedAt - id,
                    stopFailures = it.stopFailures - id,
                    uncancellable = it.uncancellable - id,
                    permissions = it.permissions.filterNot { request -> request.sessionId == id },
                )
            }
        }
    }

    override suspend fun isWorktree(id: String): Boolean {
        log.d { "Read conversation execution mode" }
        return record(id).worktreeTaskId != null
    }

    override suspend fun openTurn(id: String, settings: RunSettings): ActiveSession {
        log.i { "Open the conversation's fixed execution workspace" }
        val active = open(id, target(id, settings))
        update(id) {
            copy(
                updatedAt = clock.now(),
                hasFailed = false,
                failureKind = RunFailureKind.Unknown,
            )
        }
        return active
    }

    override suspend fun submitTurn(active: ActiveSession, request: StudioTurnRequest): TurnId {
        log.i { "Submit the reserved native request" }
        val target = checkNotNull(record(request.id).target)
        return submitConfigured(request.id, active, target, request.prompt, request.settings, request.request)
    }

    override suspend fun shouldStop(id: String): Boolean {
        log.d { "Read pending native stop request" }
        return handlesLock.withLock { id in stopRequests }
    }

    override suspend fun failedTurn(id: String, error: Exception): RunOutcome {
        log.e(error) { "Persist failed native turn" }
        val kind = (error as? EngineException)?.failure.toRunFailureKind()
        update(id) { copy(hasFailed = true, failureKind = kind) }
        return RunOutcome.Failed
    }

    /** Initial defaults are used once; accepted execution keeps the session's confirmed values. */
    private suspend fun submitConfigured(
        id: String,
        active: ActiveSession,
        target: EngineTarget,
        prompt: String,
        settings: RunSettings,
        requestId: RequestId,
    ): TurnId {
        val stored = state.value.configurations[id]?.applied ?: record(id).configuration
        val trust = (stored?.approval ?: settings.approval).trustFor(offeredModels.value, target)
        val effort = if (stored != null) {
            stored.reasoningEffort
        } else {
            efforts.state.value.effectiveEffort(target, offeredModels.value.reasoningEfforts(target))
        }
        log.i { "Submitting prompt length=${prompt.length} trust=${trust ?: "default"}" }
        val turn = nativeSession.submit(active, prompt, effort, trust, requestId)
        val capability = active.features.resolve(ChangesSessionConfiguration) as? FeatureAccess.Available
        val confirmed = capability?.feature?.configuration?.value ?: SessionConfiguration(target.model, effort, trust)
        configurationState(id, StudioSessionConfiguration(confirmed.studio(target)))
        try {
            saveConfiguration(id, confirmed.studio(target))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Accepted turn configuration could not be saved; continue native observation" }
        }
        configurations.observe(this, id, active, target)
        return turn
    }

    private suspend fun target(id: String, settings: RunSettings): EngineTarget {
        val selected = selections.observe().first()
        val record = record(id)
        val chosen = state.value.configurations[id]?.applied?.modelId
            ?: record.configuration?.modelId ?: record.target?.studioModelId() ?: settings.modelId
        val target = requireNotNull(turnTarget(chosen, record.target, selected.defaultTarget)) {
            "Select a connected model in settings"
        }
        check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
        return target
    }

    override suspend fun configure(sessionId: String, change: StudioSettingChange) {
        log.i { "Configure session parameter kind=${change::class.simpleName.orEmpty()}" }
        configurations.configure(this, sessionId, change)
    }

    override suspend fun configurationRecord(id: String): StudioChatRecord {
        log.d { "Read session configuration id=$id" }
        val record = record(id)
        val applied = state.value.configurations[id]?.applied ?: return record
        return record.copy(configuration = applied, target = studioModelTarget(applied.modelId) ?: record.target)
    }

    override suspend fun configurationSession(id: String, target: EngineTarget): ActiveSession {
        log.d { "Resolve configuration handle id=$id engine=${target.engine.value}" }
        return open(id, target)
    }

    override suspend fun configurationAnchor(id: String): FeedbackAnchor = lock.withLock {
        log.d { "Read configuration transcript anchor" }
        FeedbackAnchor(record(id).ref, transcripts.read(id).lastOrNull()?.info?.id)
    }

    override suspend fun saveConfiguration(id: String, settings: StudioSessionSettings) {
        log.d { "Save confirmed configuration id=$id" }
        val target = requireNotNull(studioModelTarget(settings.modelId))
        update(id) {
            val current = state.value.configurations[id]?.applied
            if (current != null && current != settings) this else copy(configuration = settings, target = target)
        }
    }

    override suspend fun validateConfigurationTarget(target: EngineTarget) {
        log.d { "Validate configuration route engine=${target.engine.value}" }
        if (!selections.observe().first().isEnabled(target)) {
            throw EngineException(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
    }

    override fun configurationModels(): List<StudioModel> {
        log.d { "Read configuration model choices" }
        return offeredModels.value
    }

    override fun configurationState(id: String, state: StudioSessionConfiguration) {
        log.d { "Reflect configuration id=$id pending=${state.pendingOperation != null}" }
        mutableState.update { it.copy(configurations = it.configurations + (id to state)) }
    }

    override suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome = when (outcome) {
        TurnOutcome.Completed -> RunOutcome.Completed

        TurnOutcome.Cancelled -> RunOutcome.Stopped

        TurnOutcome.Unknown, null, is TurnOutcome.Failed -> {
            log.w {
                "Native turn did not complete successfully type=${outcome?.let { it::class.simpleName }.orEmpty()} " +
                    "code=${(outcome as? TurnOutcome.Failed)?.failure?.code.orEmpty()}"
            }
            val kind = (outcome as? TurnOutcome.Failed)?.failure.toRunFailureKind()
            update(id) { copy(hasFailed = true, failureKind = kind) }
            RunOutcome.Failed
        }
    }

    override suspend fun requestStop(id: String, active: ActiveSession, turn: TurnId) {
        log.i { "Request native cancellation" }
        if (!nativeSession.requestStop(active, turn)) {
            mutableState.update { it.copy(stopFailures = it.stopFailures + id) }
        }
    }

    override suspend fun refreshHistory(id: String, history: SessionHistory) {
        log.d { "Refresh terminal native history" }
        nativeSession.mirrorHistory(historyMirror, id, history, isFinal = true)
    }

    override suspend fun observeHistory(id: String, history: SessionHistory) {
        log.d { "Follow accepted native history" }
        nativeSession.mirrorHistory(historyMirror, id, history, isFinal = false)
    }

    private suspend fun open(id: String, target: EngineTarget): ActiveSession = withChatLock(id) {
        val record = record(id)
        val workspace = record.projectId?.let { projectId ->
            check(
                facade.engines.state.value.any {
                    it.descriptor.id == target.engine &&
                        it.descriptor.isLocalWorkspaceSupported
                },
            ) {
                "Choose a model with local project access"
            }
            val ref = record.executionWorkspace ?: WorkspaceRef(projectId)
            checkNotNull(workspaces.resolve(ref)) { "The project folder is unavailable" }
            ref
        }
        val current = handlesLock.withLock { handles[id] }
        if (current != null) {
            check(
                current.route.engine == target.engine && current.route.binding == target.binding,
            ) { "Start a new conversation to change engine or connection" }
            check(current.route.workspace == workspace) { "The conversation workspace cannot change" }
            if (current.configurationModel(record.target?.model) != target.model) {
                current.features.requireFeature(SwitchesModels).switchTo(target.model)
            }
            update(id) { copy(target = target) }
            return@withChatLock current
        }
        val active = if (record.ref == null) {
            facade.engines.features(target.engine).requireFeature(CreatesSessions)
                .create(CreateSessionRequest(target, workspace))
        } else {
            check(
                record.target?.engine == target.engine && record.target.binding == target.binding,
            ) { "The stored session uses another connection" }
            facade.sessions.get(record.ref).features.requireFeature(ResumesSessions)
                .resume(ResumeSessionRequest(target, workspace))
        }
        // Register before persisting: the stored ref recomputes continuability, which must see the live handle.
        handlesLock.withLock { handles[id] = active }
        persistReference(id, target, active)
        usage.attach(id, active)
        active
    }

    private suspend fun persistReference(id: String, target: EngineTarget, active: ActiveSession) {
        var isPersisted = false
        try {
            update(id) { copy(ref = active.ref, target = target) }
            isPersisted = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Could not persist the native session reference; closing the session" }
            throw e
        } finally {
            if (!isPersisted) {
                withContext(NonCancellable) {
                    handlesLock.withLock { if (handles[id] === active) handles.remove(id) }
                    nativeSession.closeOrphan(active)
                }
            }
        }
    }

    /** Runs [block] under the lock of conversation [id]; the lock entry is dropped once nobody uses it. */
    private suspend fun <T> withChatLock(id: String, block: suspend () -> T): T {
        val entry = handlesLock.withLock { opening.getOrPut(id) { ChatLock() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                handlesLock.withLock {
                    entry.users--
                    if (entry.users == 0 && opening[id] === entry) opening.remove(id)
                }
            }
        }
    }

    private class ChatLock {
        val mutex = Mutex()
        var users = 0
    }

    override suspend fun cancel(sessionId: String) {
        val active = handlesLock.withLock {
            if (sessionId !in state.value.running) {
                log.i { "Stop ignored: the conversation is idle" }
                return
            }
            log.i { "Explicit stop requested" }
            mutableState.update { it.copy(stopFailures = it.stopFailures - sessionId) }
            stopRequests += sessionId
            handles[sessionId]
        } ?: return
        worktrees.cancelBuilds(sessionId)
        if (active.state.value is ActiveSessionState.Unavailable) {
            active.features.requireFeature(
                ReconcilesSession,
            ).synchronize()
        }
        val turn = active.state.value.activeTurn() ?: return
        active.features.requireFeature(CancelsTurns).cancel(turn.id)
    }

    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) {
        val active = handlesLock.withLock { handles[sessionId] }
            ?: rejectPermission("no open native session", requestId, optionId)
        val pending = active.state.value as? ActiveSessionState.AwaitingUserAction
            ?: rejectPermission("the session no longer awaits a decision", requestId, optionId)
        val request = pending.requests.firstOrNull { it.id.value == requestId }
            ?: rejectPermission("the request is no longer pending", requestId, optionId)
        val option = request.options.firstOrNull { it.id.value == optionId }
            ?: rejectPermission("the option is not offered", requestId, optionId)
        log.i { "Responding to pending engine permission" }
        active.features.requireFeature(
            RequestsPermissions,
        ).respond(PermissionDecision(request.turn, request.id, option.id, answer?.toFacade()))
    }

    /** Fails the answer so the machine shows the request again instead of hiding it forever. */
    private fun rejectPermission(reason: String, requestId: String, optionId: String): Nothing {
        log.w { "Permission answer rejected: $reason requestId=$requestId optionId=$optionId" }
        error("Permission answer rejected: $reason")
    }

    override suspend fun updatePermissions(id: String, state: ActiveSessionState) {
        log.d { "Update pending permission projection" }
        val pending = (state as? ActiveSessionState.AwaitingUserAction)?.requests.orEmpty().map { it.toStudio(id) }
        val (handle, isStopRequested) = handlesLock.withLock { handles[id] to (id in stopRequests) }
        val isStopSupported = handle?.features?.resolve(CancelsTurns) != FeatureAccess.Unsupported
        val isStopFailed = isStopRequested && state is ActiveSessionState.Unavailable && state.activeTurn != null
        mutableState.update {
            it.copy(
                permissions = it.permissions.filterNot { request -> request.sessionId == id } + pending,
                stopFailures = if (isStopFailed) it.stopFailures + id else it.stopFailures,
                uncancellable = if (isStopSupported) it.uncancellable - id else it.uncancellable + id,
            )
        }
    }

    override suspend fun edit(sessionId: String, edit: SessionEdit) {
        log.i { "Edit conversation metadata type=${edit::class.simpleName.orEmpty()}" }
        if (edit is SessionEdit.SetArchived && edit.isArchived && sessionId !in state.value.running) {
            release(sessionId)
        }
        update(sessionId) {
            when (edit) {
                is SessionEdit.Rename -> copy(title = edit.title)
                is SessionEdit.SetArchived -> copy(isArchived = edit.isArchived)
                is SessionEdit.SetPinned -> copy(isPinned = edit.isPinned)
                is SessionEdit.SetUnread -> copy(isUnread = edit.isUnread)
            }
        }
    }

    /** Closes under the conversation lock, so a concurrent open never receives a closing handle. */
    private suspend fun release(id: String) = withChatLock(id) {
        val handle = handlesLock.withLock { handles[id] } ?: return@withChatLock
        handle.close()
        handlesLock.withLock { if (handles[id] === handle) handles.remove(id) }
        log.i { "Released native session of an archived conversation" }
    }

    // Detekt cannot resolve the cross-module generic store.get here; the Kotlin compiler requires suspend.
    @Suppress("RedundantSuspendModifier")
    private suspend fun record(id: String): StudioChatRecord =
        store.get(ChatsKey).orEmpty().firstOrNull { it.id == id } ?: error("Unknown studio conversation")

    private suspend fun releaseArchived(id: String) {
        try {
            if (store.get(ChatsKey).orEmpty().firstOrNull { it.id == id }?.isArchived == true) release(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Could not release archived session; preserve its handle for retry" }
        }
    }

    private suspend fun update(id: String, change: StudioChatRecord.() -> StudioChatRecord) = lock.withLock {
        updateRecord(id, change)
    }

    /** Read-modify-write of the record list; the caller holds [lock]. */
    private suspend fun updateRecord(id: String, change: StudioChatRecord.() -> StudioChatRecord) {
        store.set(ChatsKey, store.get(ChatsKey).orEmpty().map { if (it.id == id) it.change() else it })
    }

    /** Stored items of conversation [id], moved out of its record when an earlier version kept them there. */
    private suspend fun items(id: String): List<SessionItem> = lock.withLock { transcripts.read(id) }

    /** Applies [change] to the stored items of conversation [id]. */
    private suspend fun write(id: String, change: (List<SessionItem>) -> List<SessionItem>) = lock.withLock {
        val stored = transcripts.read(id)
        transcripts.replace(id, change(stored), previousItems = stored)
    }

    override fun newMessageId(): String {
        log.d { "Allocate message identity" }
        return Uuid.random().toString()
    }
    override suspend fun append(sessionId: String, message: StudioMessage) {
        log.w { "Rejected local transcript append" }
        error("Native history owns transcript writes")
    }

    override suspend fun replace(sessionId: String, message: StudioMessage) {
        log.w { "Rejected local transcript replacement" }
        error("Native history owns transcript writes")
    }

    override suspend fun setBranch(sessionId: String, branch: String) {
        log.w { "Rejected local branch mutation" }
        error("Native tools own branch metadata")
    }
}

/** Native IO reports failures while the repository owns conversation identity and UI state. */
private class StudioNativeSessionOperations {
    private val log = Log.tag("StudioNativeSessionOperations")

    suspend fun submit(
        active: ActiveSession,
        prompt: String,
        reasoningEffort: String?,
        trust: TrustLevel?,
        requestId: RequestId,
    ): TurnId {
        log.i { "Send the native request" }
        val request = PromptRequest(
            requestId,
            listOf(ContentPart.Text(prompt)),
            reasoningEffort = reasoningEffort,
            trust = trust,
        )
        return try {
            active.features.requireFeature(SendsPrompts).send(request)
        } catch (e: EngineException) {
            log.e(e) { "Submission failed; inspect native acceptance before changing run status" }
            val accepted = active.state.value.activeTurn()?.takeIf { it.request == request.id }
            if (accepted != null) accepted.id else throw e
        }
    }

    suspend fun mirrorHistory(mirror: StudioHistoryMirror, id: String, history: SessionHistory, isFinal: Boolean) {
        try {
            if (isFinal) mirror.refresh(id, history) else mirror.follow(id, history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) {
                if (isFinal) {
                    "Final history refresh failed"
                } else {
                    "History observation failed; the native turn remains active"
                }
            }
        }
    }

    suspend fun requestStop(active: ActiveSession, turn: TurnId): Boolean = try {
        active.features.requireFeature(CancelsTurns).cancel(turn)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Native cancellation failed; continue observing the accepted turn" }
        false
    }

    /** Closes a native session nobody can reach any more, so it is not leaked. */
    suspend fun closeOrphan(active: ActiveSession) = withContext(NonCancellable) {
        try {
            active.close()
            log.i { "Closed unreferenced native session" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Could not close unreferenced native session" }
        }
    }
}

/** The model chosen for this turn wins; a chat keeps its stored route only when none was chosen. */
internal fun turnTarget(modelId: String, stored: EngineTarget?, default: EngineTarget?): EngineTarget? =
    modelId.takeIf { it.isNotBlank() }?.let {
        Json.decodeFromString(
            EngineTarget.serializer(),
            it,
        )
    } ?: stored ?: default

internal fun <F : EngineFeature> EngineFeatures.requireFeature(key: EngineFeatureKey<F>): F =
    when (val access = resolve(key)) {
        is FeatureAccess.Available -> access.feature

        is FeatureAccess.Unavailable -> throw EngineException(access.reason)

        FeatureAccess.Unsupported -> throw EngineException(
            EngineFailure.Access(AccessFailureReason.OperationNotAllowed),
        )
    }

/** Native confirmation wins over a stored model that may lag behind a failed preference write. */
internal fun ActiveSession.configurationModel(fallback: ModelId?): ModelId? =
    (features.resolve(ChangesSessionConfiguration) as? FeatureAccess.Available)?.feature?.configuration?.value?.model
        ?: fallback

private fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}

/** Trust level of a turn on [target]: only engines applying trust levels receive one. */
internal fun ApprovalMode.trustFor(offered: List<StudioModel>, target: EngineTarget): TrustLevel? =
    toTrust().takeIf { offered.isTrustSupported(target) }

private fun ApprovalMode.toTrust(): TrustLevel = when (this) {
    ApprovalMode.Ask -> TrustLevel.Ask
    ApprovalMode.AutoEdits -> TrustLevel.AutoEdits
    ApprovalMode.AutoApprove -> TrustLevel.Full
}
