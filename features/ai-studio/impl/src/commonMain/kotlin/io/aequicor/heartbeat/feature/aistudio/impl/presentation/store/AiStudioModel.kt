package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineKey
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private typealias StudioPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/**
 * Feature-scoped screen store. Mirrors the machine (panes, runs, preferences), the repository (projects,
 * sessions and transcripts of open panes) and keeps local input (drafts, sidebar). Business decisions stay
 * with the machine: the store forwards intents and clears a draft only once the machine accepted it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SingleIn(AiStudioScope::class)
@Inject
class AiStudioModel(
    private val machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    private val backend: StudioBackend,
    private val clock: Clock,
    @ForScope(AiStudioScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
    private val entries: StudioEntries,
    private val efforts: EffortChoicesView,
    private val machines: MachineRegistry,
) {
    private val log = Log.tag("AiStudioModel")

    val store = factory.create<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>(
        name = "AiStudio",
        initial = AiStudioScreenState(now = clock.now()).reflectMachine(machine.state.value),
        // Failures are logged by the store factory; the workspace stays usable instead of a dead-end error.
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            when (output) {
                is AiStudioOutput.SubmitFailed -> updateState { restoreDraft(output.paneId, output.prompt) }

                // Delivery results of engine questions are handled by the question bridge.
                is AiStudioOutput.PermissionAnswerFailed, is AiStudioOutput.RunEnded -> Unit
            }
        }) { reflectMachine(it) }
        whileSubscribed(name = "workspace") {
            val pipeline = this
            coroutineScope {
                launch { observeWorkspace(pipeline) }
                launch { observeResearch(pipeline) }
                launch { observeEfforts(pipeline) }
                launch { observeWorktrees(pipeline) }
                launch { observeUsageTargets() }
                // Display only: the machine picks a default model from the same offer.
                launch {
                    backend.repository().observeModels().collect { models ->
                        updateState {
                            copy(
                                models = models.map { it.toUi() }.toImmutableList(),
                            )
                        }
                    }
                }
                launch { observeTranscripts(pipeline) }
                launch { observeClock(pipeline) }
            }
        }
        reduce { intent -> handle(this, intent) }
    }

    init {
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch { machine.send(AiStudioIntent.Public.Start) }
    }

    private suspend fun observeUsageTargets() {
        try {
            combine(machine.state, backend.repository().observeWorkspace()) { state, workspace ->
                val ready = state as? AiStudioState.Ready
                if (ready == null) {
                    emptySet()
                } else {
                    buildSet {
                        add(ready.settings.modelId)
                        val visible = ready.panes.mapNotNull { it.sessionId }.toSet()
                        visible.forEach { id ->
                            val model = ready.configurations[id]?.applied?.modelId ?: workspace.session(id)?.modelId
                            model?.let { add(it) }
                        }
                    }.filterTo(mutableSetOf()) { it.isNotBlank() }
                }
            }.distinctUntilChanged().collect { ids ->
                machine.send(AiStudioIntent.Public.ObserveUsageTargets(ids))
            }
        } finally {
            withContext(NonCancellable) {
                machine.send(AiStudioIntent.Public.ObserveUsageTargets(emptySet()))
            }
        }
    }

    private suspend fun observeEfforts(pipeline: StudioPipeline) {
        with(pipeline) {
            efforts.state.collect { updateState { copy(settings = settings.copy(engineEfforts = it.studioEfforts())) } }
        }
    }

    private suspend fun observeResearch(pipeline: StudioPipeline) = with(pipeline) {
        entries.showsResearch.collect { updateState { copy(isResearchEnabled = it) } }
    }

    private suspend fun observeWorktrees(pipeline: StudioPipeline) = with(pipeline) {
        machines.observe(WorktreeMachineKey).flatMapLatest { ref ->
            ref?.state ?: flowOf(WorktreeState.Idle)
        }.collect { snapshot ->
            updateState {
                when (snapshot) {
                    is WorktreeState.Ready -> copy(
                        worktreeJournal = WorktreeJournalUi.Ready,
                        worktrees = snapshot.tasks.mapValues { it.value.toUi() }.toImmutableMap(),
                    )

                    WorktreeState.LoadError -> copy(worktreeJournal = WorktreeJournalUi.Error)

                    WorktreeState.Idle, WorktreeState.Loading -> copy(worktreeJournal = WorktreeJournalUi.Loading)
                }
            }
        }
    }

    private suspend fun observeWorkspace(pipeline: StudioPipeline) = with(pipeline) {
        backend.repository().observeWorkspace().collect { workspace -> updateState { withWorkspace(workspace) } }
    }

    private suspend fun observeTranscripts(pipeline: StudioPipeline) = with(pipeline) {
        machine.state
            .map { state ->
                (state as? AiStudioState.Ready)?.panes?.asSequence()?.mapNotNull {
                    it.sessionId
                }?.distinct()?.toList().orEmpty()
            }
            .distinctUntilChanged()
            .flatMapLatest { ids -> transcriptsOf(ids) }
            .collect { transcripts -> updateState { copy(transcripts = transcripts.toImmutableMap()) } }
    }

    private fun transcriptsOf(ids: List<String>): Flow<Map<String, ImmutableList<MessageUi>>> = if (ids.isEmpty()) {
        flowOf(emptyMap())
    } else {
        flow {
            val repository = backend.repository()
            emitAll(
                combine(
                    ids.map { id ->
                        repository.observeMessages(id).map { messages ->
                            id to messages.map { it.toUi() }.toImmutableList()
                        }
                    },
                ) { it.toMap() },
            )
        }
    }

    /** Active runs tick each second; idle screens refresh once a minute for local Today/Yesterday labels. */
    private suspend fun observeClock(pipeline: StudioPipeline) = with(pipeline) {
        machine.state
            .map { (it as? AiStudioState.Ready)?.running?.isNotEmpty() == true }
            .distinctUntilChanged()
            .collectLatest { isRunning ->
                while (currentCoroutineContext().isActive) {
                    updateState { copy(now = clock.now()) }
                    delay(if (isRunning) 1.seconds else 1.minutes)
                }
            }
    }

    private suspend fun handle(pipeline: StudioPipeline, intent: AiStudioScreenIntent) = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.Navigation -> navigate(pipeline, intent)
            is AiStudioScreenIntent.Composer -> compose(pipeline, intent)
            is AiStudioScreenIntent.SessionAction -> act(pipeline, intent)
            is AiStudioScreenIntent.Sidebar -> updateState { copy(sidebar = sidebar.reduce(intent)) }
        }
    }

    private suspend fun navigate(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Navigation) = with(pipeline) {
        val command = when (intent) {
            AiStudioScreenIntent.Retry -> AiStudioIntent.Public.Retry

            is AiStudioScreenIntent.AddProject -> AiStudioIntent.Public.AddProject(intent.paneId)

            is AiStudioScreenIntent.NewSession -> AiStudioIntent.Public.NewSession(intent.projectId)

            is AiStudioScreenIntent.SelectProject -> AiStudioIntent.Public.SelectProject(
                intent.paneId,
                intent.projectId,
            )

            is AiStudioScreenIntent.OpenSession -> AiStudioIntent.Public.OpenSession(intent.sessionId)

            is AiStudioScreenIntent.OpenBeside -> AiStudioIntent.Public.OpenBeside(intent.sessionId)

            is AiStudioScreenIntent.ClosePane -> AiStudioIntent.Public.ClosePane(intent.paneId)

            is AiStudioScreenIntent.FocusPane -> AiStudioIntent.Public.FocusPane(intent.paneId)
        }
        val result = sendTo(machine, command)
        if (result == SendResult.Accepted) updateState { afterNavigation(intent) }
    }

    private suspend fun compose(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Composer) = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.Worktree -> composeWorktree(pipeline, intent)

            is AiStudioScreenIntent.RefreshUsage -> sendTo(machine, AiStudioIntent.Public.RefreshUsage(intent.modelId))

            is AiStudioScreenIntent.RespondPermission -> sendTo(
                machine,
                AiStudioIntent.Public.RespondPermission(intent.sessionId, intent.requestId, intent.optionId),
            )

            is AiStudioScreenIntent.DraftChanged -> updateState { withDraft(intent.paneId, intent.text) }

            is AiStudioScreenIntent.Submit -> withState {
                val result = sendTo(
                    machine,
                    AiStudioIntent.Public.Submit(intent.paneId, draft(intent.paneId)),
                )
                if (result == SendResult.Accepted) updateState { withDraft(intent.paneId, "") }
            }

            is AiStudioScreenIntent.Stop -> sendTo(machine, AiStudioIntent.Public.Stop(intent.sessionId))

            is AiStudioScreenIntent.SelectModel -> selectSetting(
                pipeline,
                intent.paneId,
                StudioSettingChange.Model(intent.modelId),
            ) { copy(modelId = intent.modelId) }

            is AiStudioScreenIntent.SelectEffort -> {
                val effort = if (intent.effort == EffortUi.VeryHigh) "xhigh" else intent.effort.name.lowercase()
                selectSetting(pipeline, intent.paneId, StudioSettingChange.Effort(effort)) {
                    copy(effort = intent.effort.toDomain())
                }
            }

            is AiStudioScreenIntent.SelectEngineEffort -> selectEngineEffort(pipeline, intent)

            is AiStudioScreenIntent.SelectApproval -> {
                log.i { "select approval: ${intent.approval}" }
                val change = StudioSettingChange.Approval(intent.approval.toDomain())
                selectSetting(pipeline, intent.paneId, change) { copy(approval = intent.approval.toDomain()) }
            }
        }
    }

    private suspend fun composeWorktree(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Worktree) = with(
        pipeline,
    ) {
        when (intent) {
            is AiStudioScreenIntent.SelectWorktree -> sendTo(
                machine,
                AiStudioIntent.Public.SelectWorktree(intent.paneId, intent.isEnabled),
            )

            is AiStudioScreenIntent.DecideWorktree -> machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.ChooseAction(intent.sessionId, intent.action.toDomain()),
            )

            is AiStudioScreenIntent.RecheckWorktree -> machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.Recheck(intent.sessionId),
            )

            AiStudioScreenIntent.RetryWorktreeJournal -> machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.RetryLoad,
            )

            is AiStudioScreenIntent.CancelWorktreeBuild -> machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.CancelBuild(intent.sessionId, intent.operation),
            )
        }
    }

    private suspend fun act(pipeline: StudioPipeline, intent: AiStudioScreenIntent.SessionAction) = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.SetPinned -> edit(
                pipeline,
                intent.sessionId,
                SessionEdit.SetPinned(intent.isPinned),
            )

            is AiStudioScreenIntent.SetUnread -> edit(
                pipeline,
                intent.sessionId,
                SessionEdit.SetUnread(intent.isUnread),
            )

            is AiStudioScreenIntent.SetArchived -> edit(
                pipeline,
                intent.sessionId,
                SessionEdit.SetArchived(intent.isArchived),
            )

            is AiStudioScreenIntent.StartRename -> updateState { startRename(intent.sessionId, intent.origin) }

            is AiStudioScreenIntent.RenameChanged -> updateState {
                copy(sidebar = sidebar.copy(renaming = sidebar.renaming?.copy(title = intent.title)))
            }

            AiStudioScreenIntent.CancelRename -> updateState { copy(sidebar = sidebar.copy(renaming = null)) }

            AiStudioScreenIntent.CommitRename -> withState {
                val renaming = sidebar.renaming
                updateState { copy(sidebar = sidebar.copy(renaming = null)) }
                if (renaming != null && renaming.title.trim() != session(renaming.sessionId)?.title) {
                    edit(pipeline, renaming.sessionId, SessionEdit.Rename(renaming.title))
                }
            }
        }
    }

    private suspend fun selectEngineEffort(pipeline: StudioPipeline, intent: AiStudioScreenIntent.SelectEngineEffort) =
        with(pipeline) {
            log.i { "select effort: ${intent.effort ?: "default"}" }
            if (changeSessionSetting(pipeline, intent.paneId, StudioSettingChange.Effort(intent.effort))) return@with
            val target = studioModelTarget(intent.modelId) ?: return@with log.w {
                "effort selection ignored: model is not an engine route"
            }
            val select = EffortConfigurationIntent.Public.Select(target, intent.effort)
            val result = machines.send(EffortConfigurationMachineKey, select)
            if (result != SendResult.Accepted) log.w { "effort selection not applied: $result" }
        }

    private suspend fun selectSetting(
        pipeline: StudioPipeline,
        paneId: Int?,
        change: StudioSettingChange,
        changeDefault: RunSettings.() -> RunSettings,
    ) {
        if (!changeSessionSetting(pipeline, paneId, change)) updateSettings(pipeline, changeDefault)
    }

    /** Routes an existing native chat through its machine; new pages and scripted chats keep local defaults. */
    private suspend fun changeSessionSetting(
        pipeline: StudioPipeline,
        paneId: Int?,
        change: StudioSettingChange,
    ): Boolean = with(pipeline) {
        var isHandled = true
        withState {
            val pane = panes.firstOrNull { it.id == (paneId ?: focusedPaneId) } ?: return@withState
            isHandled = false
            val sessionId = pane.sessionId ?: return@withState
            val modelId = configurations[sessionId]?.modelId ?: session(sessionId)?.modelId ?: settings.modelId
            if (studioModelTarget(modelId) == null) return@withState
            isHandled = true
            sendTo(machine, AiStudioIntent.Public.ChangeSessionSetting(sessionId, change))
        }
        isHandled
    }

    private suspend fun edit(pipeline: StudioPipeline, sessionId: String, edit: SessionEdit) = with(pipeline) {
        sendTo(machine, AiStudioIntent.Public.Edit(sessionId, edit))
    }

    private suspend fun updateSettings(pipeline: StudioPipeline, change: RunSettings.() -> RunSettings) = with(
        pipeline,
    ) {
        withState {
            val current = RunSettings(
                settings.modelId,
                settings.effort.toDomain(),
                settings.approval.toDomain(),
            )
            sendTo(machine, AiStudioIntent.Public.UpdateSettings(current.change()))
        }
    }
}

/** Stored choices keyed by studio model id; empty until the effort machine is ready. */
private fun EffortConfigurationState.studioEfforts() = when (this) {
    EffortConfigurationState.Idle, EffortConfigurationState.Loading -> persistentMapOf()
    is EffortConfigurationState.Ready -> choices.associate { it.target.studioModelId() to it.effort }.toImmutableMap()
}
