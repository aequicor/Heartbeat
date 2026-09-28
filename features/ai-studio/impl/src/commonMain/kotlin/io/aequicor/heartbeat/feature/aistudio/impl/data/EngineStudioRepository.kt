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
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.effectiveEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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

/** Persistent identity and projection; no credential material or opaque history cursors are stored. */
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
    val projectId: String? = null,
)

/** The profile owns accepted turns, handles and transcript projection; screens only observe. */
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
) : StudioRepository,
    StudioRuntime {
    private val log = Log.tag("EngineStudio")
    private val store = stores.keyValue(ChatSpec)
    private val lock = Mutex()
    private val historyMirror = StudioHistoryMirror(
        read = { id -> record(id).items },
        update = { id, change -> update(id, change) },
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

    private val offeredModels = facade.observeStudioModels(selections, sources)
        .stateIn(profile.coroutineScope, SharingStarted.WhileSubscribed(), emptyList())

    /** Recomputed only when stored refs, engines or running chats change; storage writes per event do not. */
    private val continuability: Flow<Map<String, Boolean>> = combine(
        store.observe(ChatsKey).map { records -> records.orEmpty().map { it.id to it.ref } }.distinctUntilChanged(),
        facade.engines.state,
        state.map { it.running }.distinctUntilChanged(),
    ) { refs, _, _ ->
        log.d { "Recompute conversation continuability count=${refs.size}" }
        refs.associate { (id, ref) -> id to isContinuable(id, ref) }
    }

    override fun observeWorkspace(): Flow<StudioWorkspace> = combine(
        store.observe(ChatsKey),
        continuability,
        workspaces.observe(),
    ) { records, continuable, projects ->
        log.d { "observeWorkspace count=${records.orEmpty().size}" }
        StudioWorkspace(
            projects.map { StudioProject(it.ref.value, it.name, StudioEnvironment.Local, "") },
            records.orEmpty().map {
                StudioSession(
                    it.id,
                    it.projectId,
                    it.title,
                    it.updatedAt,
                    it.isPinned,
                    it.isUnread,
                    it.isArchived,
                    modelId = it.target?.let { target -> Json.encodeToString(EngineTarget.serializer(), target) },
                    isContinuable = continuable[it.id] ?: true,
                )
            },
        )
    }

    private suspend fun isContinuable(id: String, ref: SessionRef?): Boolean {
        if (ref == null) return true
        val current = handlesLock.withLock { handles[id] }
        if (current != null) {
            return current.state.value !is ActiveSessionState.Closing &&
                current.state.value != ActiveSessionState.Closed
        }
        return try {
            facade.sessions.get(ref).features.resolve(ResumesSessions) is FeatureAccess.Available
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Stored chat cannot currently be resumed" }
            false
        }
    }

    override fun observeMessages(sessionId: String): Flow<List<StudioMessage>> = combine(
        store.observe(ChatsKey),
        state,
    ) { records, runtime ->
        log.d { "Observe transcript projection" }
        records.orEmpty().firstOrNull { it.id == sessionId }?.let { record ->
            record.items.toStudioMessages(record.updatedAt, sessionId in runtime.running) +
                if (record.hasFailed) listOf(StudioMessage.Failed("failure", record.updatedAt)) else emptyList()
        }.orEmpty()
    }

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
        if (projectId != null) {
            requireNotNull(workspaces.resolve(WorkspaceRef(projectId))) { "The project folder is unavailable" }
        }
        val record = StudioChatRecord(Uuid.random().toString(), title, clock.now(), projectId = projectId)
        lock.withLock { store.set(ChatsKey, store.get(ChatsKey).orEmpty() + record) }
        log.i { "Created studio conversation" }
        return StudioSession(record.id, record.projectId, title, record.updatedAt)
    }

    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome {
        log.i { "Start profile-owned execution" }
        val job = lock.withLock {
            check(!profile.isClosed) { "Profile is closed" }
            check(sessionId !in state.value.running) { "Session is busy" }
            val startedAt = clock.now()
            mutableState.update {
                it.copy(running = it.running + sessionId, runStartedAt = it.runStartedAt + (sessionId to startedAt))
            }
            profile.coroutineScope.async(start = CoroutineStart.LAZY) { execute(sessionId, prompt, settings) }.also {
                it.start()
            }
        }
        return job.await()
    }

    private suspend fun execute(id: String, prompt: String, settings: RunSettings): RunOutcome {
        try {
            val result = executeTurn(id, prompt, settings)
            // Profile shutdown owns native cleanup; never wait for its cancelled machine uninterruptibly.
            releaseArchived(id)
            return result
        } finally {
            log.i { "Profile execution ended; clear local run state" }
            // Atomic with cancel(): a stop is recorded only while the chat still counts as running.
            withContext(NonCancellable) {
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
        }
    }

    private suspend fun executeTurn(id: String, prompt: String, settings: RunSettings): RunOutcome {
        try {
            val target = target(id, settings)
            val active = open(id, target)
            update(id) { copy(updatedAt = clock.now(), hasFailed = false) }
            val history = active.features.requireFeature(SessionHistory)
            return supervisorScope {
                val observation = launch {
                    observeHistory(id, history)
                }
                val permissions = launch { active.state.collect { updatePermissions(id, it) } }
                try {
                    log.i { "Submitting prompt length=${prompt.length}" }
                    val turn = submit(
                        active,
                        prompt,
                        efforts.state.value.effectiveEffort(target, offeredModels.value.reasoningEfforts(target)),
                    )
                    if (handlesLock.withLock { id in stopRequests }) requestStop(id, active, turn)
                    val terminal = active.state.first {
                        (it is ActiveSessionState.Ready && it.lastTurn?.id == turn) ||
                            (it is ActiveSessionState.Unavailable && it.activeTurn == null && it.lastTurn?.id == turn)
                    }
                    observation.cancelAndJoin()
                    refreshHistory(id, history)
                    val finished =
                        (terminal as? ActiveSessionState.Ready)?.lastTurn
                            ?: (terminal as? ActiveSessionState.Unavailable)?.lastTurn
                    outcome(id, finished?.outcome)
                } finally {
                    observation.cancel()
                    permissions.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Studio execution failed" }
            update(id) { copy(hasFailed = true) }
            return RunOutcome.Failed
        }
    }

    private suspend fun target(id: String, settings: RunSettings): EngineTarget {
        val selected = selections.observe().first()
        val target = requireNotNull(turnTarget(settings.modelId, record(id).target, selected.defaultTarget)) {
            "Select a connected model in settings"
        }
        check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
        return target
    }

    private suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome = when (outcome) {
        TurnOutcome.Completed -> RunOutcome.Completed

        TurnOutcome.Cancelled -> RunOutcome.Stopped

        TurnOutcome.Unknown, null, is TurnOutcome.Failed -> {
            log.w {
                "Native turn did not complete successfully type=${outcome?.let { it::class.simpleName }.orEmpty()}"
            }
            update(id) { copy(hasFailed = true) }
            RunOutcome.Failed
        }
    }

    private suspend fun submit(active: ActiveSession, prompt: String, reasoningEffort: String?): TurnId {
        val request = PromptRequest(
            RequestId(Uuid.random().toString()),
            listOf(ContentPart.Text(prompt)),
            reasoningEffort = reasoningEffort,
        )
        return try {
            active.features.requireFeature(SendsPrompts).send(request)
        } catch (e: EngineException) {
            log.e(e) { "Submission failed; inspect native acceptance before changing run status" }
            val accepted = active.state.value.activeTurn()?.takeIf { it.request == request.id }
            if (accepted != null) accepted.id else throw e
        }
    }

    private suspend fun requestStop(id: String, active: ActiveSession, turn: TurnId) {
        try {
            active.features.requireFeature(CancelsTurns).cancel(turn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Native cancellation failed; continue observing the accepted turn" }
            mutableState.update { it.copy(stopFailures = it.stopFailures + id) }
        }
    }

    private suspend fun refreshHistory(id: String, history: SessionHistory) {
        try {
            historyMirror.refresh(id, history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Final history refresh failed" }
        }
    }

    private suspend fun observeHistory(id: String, history: SessionHistory) {
        try {
            historyMirror.follow(id, history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "History observation failed; the native turn remains active" }
        }
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
            val ref = WorkspaceRef(projectId)
            checkNotNull(workspaces.resolve(ref)) { "The project folder is unavailable" }
            ref
        }
        val current = handlesLock.withLock { handles[id] }
        if (current != null) {
            check(
                current.route.engine == target.engine && current.route.binding == target.binding,
            ) { "Start a new conversation to change engine or connection" }
            check(current.route.workspace == workspace) { "The conversation workspace cannot change" }
            if (record.target?.model != target.model) {
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
        persistReference(id, target, active)
        handlesLock.withLock { handles[id] = active }
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
            if (!isPersisted) withContext(NonCancellable) { closeOrphan(active) }
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

    /** Closes a native session nobody can reach any more, so it is not leaked. */
    private suspend fun closeOrphan(active: ActiveSession) = withContext(NonCancellable) {
        try {
            active.close()
            log.i { "Closed unreferenced native session" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Could not close unreferenced native session" }
        }
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

    private suspend fun updatePermissions(id: String, state: ActiveSessionState) {
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
        store.set(ChatsKey, store.get(ChatsKey).orEmpty().map { if (it.id == id) it.change() else it })
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

private fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}
