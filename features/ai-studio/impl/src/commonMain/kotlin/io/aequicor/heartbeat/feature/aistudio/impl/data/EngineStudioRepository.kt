package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import io.aequicor.heartbeat.feature.aistudio.impl.domain.OrganismRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioChatResolver
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPreferences
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolChoices
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
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

internal val ChatSpec = KeyValueSpec("ai_studio_chats")
internal val ChatsKey: StoreKey<List<StudioChatRecord>> =
    jsonKey("chats", ListSerializer(StudioChatRecord.serializer()))

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
    val lastRunRequest: RequestId? = null,
    val hasLastRunSucceeded: Boolean = false,
    val runRevision: Long = 0,
    val hasFailed: Boolean = false,
    val failureKind: RunFailureKind = RunFailureKind.Unknown,
    val projectId: String? = null,
    /** Managed execution identity, distinct from the project's sidebar grouping. */
    val executionWorkspace: WorkspaceRef? = null,
    val worktreeTaskId: String? = null,
    val virtualParentWorkspace: WorkspaceRef? = null,
    /** Explicit isolation choice; old records without it follow the sandbox toggle when preparation resumes. */
    val isSandbox: Boolean? = null,
    val configuration: StudioSessionSettings? = null,
    /** Set for a conversation run as the organic AI organism of the same id. */
    val organismId: String? = null,
    /** The harnesses the user chose when creating an organism chat; its organism is conceived with them. */
    val organismHarnesses: Set<String> = emptySet(),
    /** Durable helper marker, also present for helpers with no caller. */
    val helper: StudioHelperIdentity? = null,
    /** Native sessions this chat continued from after rollovers, oldest first; see `StudioRollover`. */
    val previousRefs: List<SessionRef> = emptyList(),
    /** Number of rollovers; names the frozen transcript segments. */
    val contextSegment: Int = 0,
    /** Essence of the previous session for the first accepted turn of the next one; user data, never logged. */
    val pendingSeed: String? = null,
    /** Durable replacement prepared before freezing the transcript; recovered before any native open. */
    val pendingEngineSwitch: StudioEngineSwitchJournal? = null,
    /** Inclusive frozen history boundary awaiting native acceptance on the chosen route. */
    val pendingEngineContext: String? = null,
    /** Continuation brief for the next accepted turn after a native compaction; user data, never logged. */
    val pendingBrief: String? = null,
    /** Connections awaiting confirmed restoration after an outside host required new native declarations. */
    val pendingHarnesses: Set<String>? = null,
    /** Retained while harnesses are globally off; their connections restore before the next enabled prompt. */
    val pendingHarnessSource: SessionRef? = null,
    /** Native create received the carried IDs while harnesses were enabled; retries reuse these declarations. */
    val pendingHarnessContext: SessionRef? = null,
) {
    /** Whether [session] is this chat's current or an earlier native session. */
    fun owns(session: SessionRef): Boolean = ref == session || session in previousRefs
}

/** The profile owns accepted turns, handles and transcript projection; screens only observe. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioRepository>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioRuntime>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioRunHost>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioConfigurationAccess>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioChatModes>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioTurnHost>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioChatResolver>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioHelperChatWriter>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioHelperAccess>())
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
    private val preferences: StudioPreferences,
    private val conversations: StudioConversationCreation,
    learning: StudioLearningPrompts,
    private val checklists: StudioChecklists,
    private val organisms: StudioOrganisms,
    private val configuredSubmission: StudioConfiguredSubmission,
    private val helperAdmission: StudioHelperAdmission,
    private val harnesses: StudioHarnessAttach,
    private val toggles: FeatureToggles,
    private val attachmentsCatalog: AttachmentsCatalog,
    private val switchHelpers: StudioEngineSwitchHelpers,
) : StudioRepository,
    StudioRuntime,
    StudioTurnHost,
    StudioRunHost,
    StudioConfigurationAccess,
    StudioChatResolver,
    StudioHelperChatWriter,
    StudioHelperAccess,
    StudioChatModes {
    private val log = Log.tag("EngineStudio")
    private val nativeSession = StudioNativeSessionOperations(learning)
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

    /** Guards handles, stop requests and per-chat lock entries; never held across native or storage calls. */
    private val handlesLock = Mutex()
    private val handles = mutableMapOf<String, ActiveSession>()
    private val stopRequests = mutableSetOf<String>()
    private val conversationLocks = StudioConversationLocks(handlesLock)
    private val carry = StudioContextCarry(
        conversationLocks,
        ::record,
        ::items,
        ::write,
        ::update,
        object : StudioCarriedHandles {
            override suspend fun handle(id: String): ActiveSession? = handlesLock.withLock { handles[id] }

            override suspend fun forget(id: String, closed: ActiveSession) {
                handlesLock.withLock { if (handles[id] === closed) handles.remove(id) }
            }
        },
    )
    private val sessionOpening = StudioSessionOpening(
        facade,
        workspaces,
        harnesses,
        carry,
        usage,
        StudioSessionStorage(
            { id -> handlesLock.withLock { handles.live(id) } },
            { id, active -> handlesLock.withLock { handles[id] = active } },
            ::forgetHandle,
            ::update,
            nativeSession::persistReference,
        ),
    )
    private val mutableState = MutableStateFlow(StudioRuntimeState())
    override val state: StateFlow<StudioRuntimeState> = mutableState.asStateFlow()
    private val switchAccess by lazy {
        StudioEngineSwitchAccess(
            facade,
            workspaces,
            harnesses,
            StudioEngineSwitchMutations(configurations, carry, switchHelpers),
            StudioEngineSwitchStorage(
                ::configurationRecord,
                { id -> handlesLock.withLock { handles[id] } },
                { id, target -> open(id, target, emptyMap()) },
                ::validateConfigurationTarget,
                ::configurationState,
                { offeredModels.value },
                ::publishSwitch,
                { id, active -> historyMirror.refresh(id, active.features.requireFeature(SessionHistory)) },
            ),
        )
    }

    private fun publishSwitch(
        id: String,
        state: io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchState,
        onlyIfIdle: Boolean,
    ) {
        log.i { "Engine replacement pending=${state.isPending} failure=${state.failure ?: "none"}" }
        mutableState.update {
            if (onlyIfIdle && it.engineSwitches[id]?.isPending == true) {
                it
            } else {
                it.copy(engineSwitches = it.engineSwitches + (id to state))
            }
        }
    }
    private val engineSwitcher = StudioEngineSwitcher(profile, toggles, runs, configurations, switchAccess)

    init {
        conversations.recoverPending({ lock.withLock { store.get(ChatsKey).orEmpty() } }, ::saveConversation)

        profile.coroutineScope.observeStudioWorktrees(worktrees, store, lock, conversations) { task ->
            val action = task.actionRequest ?: return@observeStudioWorktrees
            if (lock.withLock { deliveringActions.add(action.operation) }) {
                profile.coroutineScope.launch { deliverAction(task.chatId, action) }
            }
        }
        observeStudioRuntime(profile.coroutineScope, toggles, usage, store, mutableState)
    }

    override suspend fun observeUsageTargets(owner: Any, modelIds: Set<String>) {
        log.d { "Observe composer usage routes" }
        usage.observe(owner, modelIds)
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
                StudioTranscriptProjection(store, transcripts, configurations, checklists).observe(sessionId, state),
            )
        }
    }

    override fun observeModels(): Flow<List<StudioModel>> {
        log.d { "Observe enabled models and their cached capabilities" }
        return offeredModels
    }

    override suspend fun sessionIdOf(ref: SessionRef): String? {
        log.d { "sessionIdOf engine=${ref.engine.value} source=${ref.source.value}" }
        return lock.withLock { store.get(ChatsKey).orEmpty().firstOrNull { record -> record.owns(ref) }?.id }
    }

    override suspend fun defaults(): RunSettings {
        log.v { "Read start-page defaults" }
        return observeDefaults().first()
    }

    override fun observeDefaults(): Flow<RunSettings> {
        log.v { "Observe start-page defaults" }
        return observeStudioDefaults(preferences, selections, efforts)
    }

    override suspend fun saveDefaults(settings: RunSettings, version: StudioSettingsVersion) {
        log.d { "Save start-page preferences revision=${version.revision}" }
        preferences.save(settings, version)
    }

    override suspend fun defaultProjectId(): String? {
        log.d { "Read default workspace" }
        return null
    }

    override suspend fun createSession(
        projectId: String?,
        title: String,
        isWorktree: Boolean,
        organism: OrganismRequest?,
        executionWorkspace: WorkspaceRef?,
        isSandbox: Boolean?,
    ): StudioSession {
        workspaces.requireExecutionWorkspace(executionWorkspace, projectId != null && !isWorktree && organism == null)
        log.i { "Create studio conversation worktree=$isWorktree organism=${organism != null}" }
        if (projectId != null) {
            requireNotNull(workspaces.resolve(WorkspaceRef(projectId))) { "The project folder is unavailable" }
        }
        val id = Uuid.random().toString()
        if (isWorktree) requireNotNull(projectId) { "Worktree requires a local project" }
        val pending = StudioChatRecord(
            id,
            title,
            clock.now(),
            projectId = projectId,
            worktreeTaskId = id.takeIf { isWorktree },
            isSandbox = isSandbox.takeIf { isWorktree },
            executionWorkspace = executionWorkspace,
        )
        val admitted = organism?.let { organisms.admit(id, it, isWorktree, projectId).on(pending) } ?: pending
        val record = conversations.create(admitted, ::saveConversation)
        log.i { "Created studio conversation" }
        return StudioSession(record.id, record.projectId, title, record.updatedAt, isOrganism = organism != null)
    }

    override suspend fun saveHelper(record: StudioChatRecord) {
        log.v { "Save helper workspace" }
        saveHelperWorkspace(record, workspaces, worktrees, ::saveConversation)
    }

    private suspend fun saveConversation(changed: StudioChatRecord) = lock.withLock {
        val saved = store.get(ChatsKey).orEmpty()
        store.set(ChatsKey, conversations.updated(saved, changed))
    }

    /** Mark delivery durably before invoking native code; recovery never automatically sends the action again. */
    private suspend fun deliverAction(id: String, action: WorktreeActionRequest) =
        deliverStudioAction(id, action, worktrees, onFailure = { update(id) { copy(hasFailed = true) } }) {
            state.first { id !in it.running }
            val settings = hostRunSettings(record(id), state.value.configurations[id]?.applied, defaults())
            launchRun(id, action.prompt, settings, action.kind, RequestId(action.operation), waitForIdle = true) {
                worktrees.send(WorktreeIntent.Public.ActionDelivered(id, action.operation))
                worktrees.await(id, failOnTaskError = false) {
                    it.actionRequest == null && it.phase == WorktreePhase.ActionWorking &&
                        it.expectedAction?.operation == action.operation
                }
            }
        }

    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome =
        run(sessionId, prompt, settings, emptyList()) { }.also { log.i { "Studio conversation completed" } }

    override suspend fun run(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        submissionId: String,
        environment: StudioChatEnvironment?,
        onAccepted: suspend () -> Unit,
    ): RunOutcome {
        log.i { "Run studio conversation with durable attachments count=${attachments.size}" }
        requireBrowserHostSupport(record(sessionId), environment)
        val organism = organisms.submit(record(sessionId), prompt, settings, attachments, submissionId, onAccepted)
        return organism ?: launchRun(
            sessionId,
            prompt,
            settings,
            WorktreeRunKind.Coding,
            RequestId(Uuid.random().toString()),
            attachments = attachments,
            onAccepted = onAccepted,
            submissionId = submissionId,
            environment = environment,
            isUserPrompt = true,
        )
    }

    private suspend fun launchRun(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        kind: WorktreeRunKind,
        request: RequestId,
        waitForIdle: Boolean = false,
        attachments: List<ResourceRef> = emptyList(),
        submissionId: String = "",
        onAccepted: suspend () -> Unit = {},
        environment: StudioChatEnvironment? = null,
        isUserPrompt: Boolean = false,
        beforeExecute: suspend () -> Unit = {},
    ): RunOutcome = runs.run(
        this,
        StudioTurnRequest(sessionId, prompt, settings, kind, request, attachments, onAccepted)
            .copy(submissionId = submissionId, environment = environment, isUserPrompt = isUserPrompt),
        waitForIdle,
        beforeExecute = beforeExecute,
    )

    override suspend fun startedRun(id: String, at: Instant) {
        log.i { "Start profile-owned execution" }
        mutableState.update { it.copy(running = it.running + id, runStartedAt = it.runStartedAt + (id to at)) }
    }

    override suspend fun executeRun(request: StudioTurnRequest): RunOutcome {
        recoverSwitch(request.id)
        if (request.isRollover) return carry.rollover(request)
        log.i { "Execute the reserved native request" }
        val current = configurationRecord(request.id)
        val fresh = if (current.target == null && current.configuration == null) {
            request
        } else {
            request.copy(
                settings = hostRunSettings(current, current.configuration, request.settings),
            )
        }
        val admitted = helperAdmission.prepare(current, fresh)
        try {
            update(request.id) { startedRun(request.request) }
            val result = turns.execute(this, admitted)
            update(request.id) { copy(hasLastRunSucceeded = result == RunOutcome.Completed) }
            val isBriefed = request.compaction != null && request.isBriefed
            if (result == RunOutcome.Completed && isBriefed) carry.brief(request.id)
            releaseArchived(request.id)
            return result
        } finally {
            withContext(NonCancellable) { (admitted.submission as? StudioHelperSubmission)?.cancel() }
        }
    }

    override suspend fun helperRecord(helper: HelperId): StudioChatRecord {
        log.v { "Read conversation for helper supervision" }
        return record(helper.value)
    }

    override suspend fun openHelper(record: StudioChatRecord): ActiveSession {
        log.v { "Open the helper's existing native session" }
        checkNotNull(record.ref) { "Helper has no native session" }
        return open(record.id, checkNotNull(record.target), emptyMap())
    }

    override suspend fun liveHelper(helper: HelperId): ActiveSession? = handlesLock.withLock {
        log.v { "Read existing helper handle" }
        handles.live(helper.value)
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

    override suspend fun openTurn(id: String, settings: RunSettings, preselected: ToolChoices): ActiveSession {
        log.i { "Open the conversation's fixed execution workspace" }
        val active = open(id, target(id, settings), preselected)
        update(id) { copy(updatedAt = clock.now()).withoutFailure() }
        return active
    }

    override suspend fun submitTurn(active: ActiveSession, request: StudioTurnRequest): TurnId {
        log.i { "Submit the reserved native request" }
        carry.restoreHarnesses(request.id, active, harnesses)
        val target = checkNotNull(record(request.id).target)
        checklists.publishGeneration(record(request.id).copy(ref = active.ref))
        return configuredSubmission.submit(
            this,
            active,
            target,
            request,
            { offeredModels.value },
            { state.value.configurations },
        )
    }

    override suspend fun needsCarriedTools(id: String, active: ActiveSession): Boolean {
        log.v { "Check carried native declarations" }
        return record(id).needsHarnessDeclarations(active.ref, harnesses)
    }

    override suspend fun rolloverTurn(request: StudioTurnRequest, active: ActiveSession) {
        log.i { "Carry outside chat into a compatible native context" }
        carry.environment(request, active, harnesses) {
            historyMirror.refresh(request.id, active.features.requireFeature(SessionHistory))
        }
    }

    override suspend fun shouldStop(id: String): Boolean {
        log.v { "Read pending native stop request" }
        return handlesLock.withLock { id in stopRequests }
    }

    override suspend fun failedTurn(id: String, error: Exception): RunOutcome {
        log.e(error) { "Persist failed native turn" }
        val kind = (error as? EngineException)?.failure.toRunFailureKind()
        update(id) { copy(hasFailed = true, failureKind = kind) }
        return RunOutcome.Failed
    }

    private suspend fun target(id: String, settings: RunSettings): EngineTarget {
        val selected = selections.observe().first()
        val record = record(id)
        val chosen = record.turnModelId(state.value.configurations[id]?.applied, settings.modelId)
        val target = requireNotNull(turnTarget(chosen, record.target, selected.defaultTarget)) {
            "Select a connected model in settings"
        }
        check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
        return target
    }

    override suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord) {
        log.i { "Move the conversation into another execution mode" }
        update(id, change)
    }

    override suspend fun switchEngine(sessionId: String, change: StudioEngineSwitch) {
        log.i { "Request chat engine replacement" }
        engineSwitcher.switch(sessionId, change)
    }

    override suspend fun prepareRun(id: String) {
        log.v { "Recover durable chat mutations before reserved execution" }
        recoverSwitch(id)
    }

    private suspend fun recoverSwitch(id: String) = switchAccess.recoverSwitch(id)

    override suspend fun engineSwitchContext(id: String, incoming: String): String? {
        log.v { "Prepare replacement context before submission" }
        return StudioEngineSwitchContext(
            facade,
            attachmentsCatalog,
        ).prepare(record(id), incoming, items(id)) { change ->
            write(id, change)
        }
    }

    override suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean {
        log.i { "Configure session parameter kind=${change::class.simpleName.orEmpty()}" }
        return configurations.configure(this, sessionId, change)
    }

    override suspend fun configurationRecord(id: String): StudioChatRecord {
        log.v { "Read session configuration id=$id" }
        val record = record(id)
        val applied = state.value.configurations[id]?.applied ?: return record
        return record.copy(configuration = applied, target = studioModelTarget(applied.modelId) ?: record.target)
    }

    override suspend fun configurationSession(id: String, target: EngineTarget): ActiveSession {
        log.v { "Resolve configuration handle id=$id engine=${target.engine.value}" }
        return open(id, target, emptyMap())
    }

    override suspend fun configurationAnchor(id: String): FeedbackAnchor = lock.withLock {
        log.v { "Read configuration transcript anchor" }
        FeedbackAnchor(record(id).ref, transcripts.read(id).lastOrNull()?.info?.id)
    }

    override suspend fun saveConfiguration(id: String, settings: StudioSessionSettings) {
        log.v { "Save confirmed configuration id=$id" }
        val target = requireNotNull(studioModelTarget(settings.modelId))
        update(id) {
            val current = state.value.configurations[id]?.applied
            if (current != null && current != settings) this else copy(configuration = settings, target = target)
        }
    }

    override suspend fun validateConfigurationTarget(target: EngineTarget) {
        log.v { "Validate configuration route engine=${target.engine.value}" }
        if (!selections.observe().first().isEnabled(target)) {
            throw EngineException(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
    }

    override fun configurationModels(): List<StudioModel> {
        log.v { "Read configuration model choices" }
        return offeredModels.value
    }

    override fun configurationState(id: String, state: StudioSessionConfiguration) {
        log.v { "Reflect configuration id=$id pending=${state.pendingOperation != null}" }
        mutableState.update { it.copy(configurations = it.configurations + (id to state)) }
    }

    override suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome {
        log.v { "Map native turn outcome" }
        return nativeSession.outcome(outcome, isStopRequested = shouldStop(id)) {
            val kind = (outcome as? TurnOutcome.Failed)?.failure.toRunFailureKind()
            update(id) { copy(hasFailed = true, failureKind = kind) }
        }
    }

    override suspend fun requestStop(id: String, active: ActiveSession, turn: TurnId) {
        log.i { "Request native cancellation" }
        active.state.value.activeTurn()?.takeIf { it.id == turn }?.request?.let {
            worktrees.cancelBuilds(active.ref, it, turn)
        }
        if (!nativeSession.requestStop(active, turn)) {
            mutableState.update { it.copy(stopFailures = it.stopFailures + id) }
        }
    }

    override suspend fun refreshHistory(id: String, history: SessionHistory) {
        log.d { "Refresh terminal native history" }
        nativeSession.mirrorHistory(historyMirror, id, history, isFinal = true) { items(id) }
    }

    override suspend fun observeHistory(id: String, history: SessionHistory) {
        log.d { "Follow accepted native history" }
        nativeSession.mirrorHistory(historyMirror, id, history, isFinal = false)
    }

    private suspend fun open(id: String, target: EngineTarget, chosen: ToolChoices): ActiveSession {
        recoverSwitch(id)
        return openCurrent(id, target, chosen)
    }

    private suspend fun openCurrent(id: String, target: EngineTarget, chosen: ToolChoices) = conversationLocks.withLock(
        id,
    ) {
        sessionOpening.open(id, target, chosen)
    }

    private suspend fun forgetHandle(id: String, active: ActiveSession) = handlesLock.withLock {
        if (handles[id] === active) handles.remove(id)
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
        if (active.state.value is ActiveSessionState.Unavailable) {
            active.features.requireFeature(
                ReconcilesSession,
            ).synchronize()
        }
        val turn = active.state.value.activeTurn() ?: return
        turn.request?.let { worktrees.cancelBuilds(active.ref, it, turn.id) }
        active.features.requireFeature(CancelsTurns).cancel(turn.id)
    }

    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) {
        log.i { "Respond to pending permission" }
        handlesLock.withLock { handles[sessionId] }.respondStudioPermission(requestId, optionId, answer)
    }

    override suspend fun respond(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer?) {
        log.i { "Respond to captured permission" }
        handlesLock.withLock { handles[permission.sessionId] }.respondStudioPermission(permission, optionId, answer)
    }

    /** Fails the answer so the machine shows the request again instead of hiding it forever. */
    override suspend fun updatePermissions(id: String, state: ActiveSessionState) {
        log.v { "Update pending permission projection" }
        val (handle, isStopRequested) = handlesLock.withLock { handles[id] to (id in stopRequests) }
        val isStopSupported = handle?.features?.resolve(CancelsTurns) != FeatureAccess.Unsupported
        mutableState.update { it.withPermissionState(id, state, isStopRequested, isStopSupported) }
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

    override suspend fun consumeSeed(id: String) = update(id) { seeded() }.also { log.d { "seeded" } }

    /** Closes under the conversation lock, so a concurrent open never receives a closing handle. */
    private suspend fun release(id: String) = conversationLocks.withLock(id) {
        val handle = handlesLock.withLock { handles[id] } ?: return@withLock
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

    override fun newMessageId(): String = Uuid.random().toString().also { log.d { "Allocate message identity" } }
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
internal class StudioNativeSessionOperations(private val learning: StudioLearningPrompts) {
    private val log = Log.tag("StudioNativeSessionOperations")

    suspend fun outcome(outcome: TurnOutcome?, isStopRequested: Boolean, onFailure: suspend () -> Unit): RunOutcome =
        nativeRunOutcome(outcome, isStopRequested, onFailure)

    /** Mirrors native history; after the final refresh the finished turn read by [finished] is checked for hints. */
    suspend fun mirrorHistory(
        mirror: StudioHistoryMirror,
        id: String,
        history: SessionHistory,
        isFinal: Boolean,
        finished: (suspend () -> List<SessionItem>)? = null,
    ) {
        try {
            if (isFinal) mirror.refresh(id, history) else mirror.follow(id, history)
            if (isFinal && finished != null) learning.observeTurn(id, finished)
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

    /** A native handle becomes owned only after its reference and declaration receipt are durable. */
    suspend fun persistReference(active: ActiveSession, write: suspend () -> Unit, forget: suspend () -> Unit) {
        var isPersisted = false
        try {
            write()
            isPersisted = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Could not persist the native session reference; closing the session" }
            throw e
        } finally {
            if (!isPersisted) {
                withContext(NonCancellable) {
                    forget()
                    closeOrphan(active)
                }
            }
        }
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

/** The model of the next turn: the applied, then the stored configuration, then the chat's route, then [fallback]. */
internal fun StudioChatRecord.turnModelId(applied: StudioSessionSettings?, fallback: String): String =
    applied?.modelId ?: configuration?.modelId ?: target?.studioModelId() ?: fallback

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

/** Trust level of a turn on [target]: only engines applying trust levels receive one. */
internal fun ApprovalMode.trustFor(offered: List<StudioModel>, target: EngineTarget): TrustLevel? =
    toTrust().takeIf { offered.isTrustSupported(target) }

internal fun ApprovalMode.toTrust(): TrustLevel = when (this) {
    ApprovalMode.Ask -> TrustLevel.Ask
    ApprovalMode.AutoEdits -> TrustLevel.AutoEdits
    ApprovalMode.AutoApprove -> TrustLevel.Full
}

/** Studio presents tool calls and permissions, so its own sessions opt into profile instructions and hooks. */
internal fun studioCreateRequest(
    target: EngineTarget,
    workspace: WorkspaceRef?,
    preselected: ToolChoices,
): CreateSessionRequest = CreateSessionRequest(
    target,
    workspace,
    areDetachedToolsEnabled = true,
    areSessionHooksEnabled = true,
    preselected = preselected,
)

/** Resuming another segment retains the same Studio ownership contract. */
internal fun studioResumeRequest(target: EngineTarget, workspace: WorkspaceRef?): ResumeSessionRequest =
    ResumeSessionRequest(target, workspace, areDetachedToolsEnabled = true, areSessionHooksEnabled = true)

private suspend fun LocalWorkspaces.requireExecutionWorkspace(workspace: WorkspaceRef?, isSupported: Boolean) {
    require(workspace == null || isSupported)
    if (workspace != null) requireNotNull(resolve(workspace)) { "The execution folder is unavailable" }
}

/** Organism children have independent native sessions and cannot share a single browser-turn binding. */
private fun requireBrowserHostSupport(chat: StudioChatRecord, environment: StudioChatEnvironment?) {
    check(environment == null || chat.organismId == null) { "Browser-hosted turns are unavailable for organism chats" }
}

/** A failed action remains journaled for explicit recovery, including a failed failure write. */
private suspend fun deliverStudioAction(
    id: String,
    action: WorktreeActionRequest,
    worktrees: StudioWorktrees,
    onFailure: suspend () -> Unit,
    body: suspend () -> Unit,
) {
    val log = Log.tag("StudioActionDelivery")
    try {
        body()
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
        onFailure()
    }
}
