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
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAttachmentPreviews
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
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
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.uuid.Uuid

private typealias StudioPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/**
 * Feature-scoped screen store. Mirrors the machine (panes, runs, preferences), the repository (projects,
 * sessions and transcripts of open panes) and keeps local input (drafts, sidebar). Business decisions stay
 * with the machine: the store forwards intents and clears a draft only after native engine acceptance.
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
    private val attachmentsCatalog: AttachmentsCatalog,
    previews: StudioAttachmentPreviews,
) {
    private val log = Log.tag("AiStudioModel")

    /** Navigation is executed by the lifecycle component, never by a retained IO scope. */
    val attachmentNavigation = MutableSharedFlow<StudioAttachmentNavigation>(extraBufferCapacity = 8)
    private val previewRequests = MutableStateFlow<List<ResourceRef>>(emptyList())
    private val organisms = StudioOrganismView(machine, machines, backend) { it.withAttachmentMetadata() }

    val store = factory.create<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>(
        name = "AiStudio",
        initial = AiStudioScreenState(now = clock.now()).reflectMachine(machine.state.value),
        // Failures are logged by the store factory; the workspace stays usable instead of a dead-end error.
        onError = { this },
    ) {
        install {
            name = "attachment-preview-visibility"
            onState { _, next ->
                val requests = next.visibleAttachmentPreviews.map { (id, visible) ->
                    ResourceRef("attachment:$id", visible.mediaType)
                }
                if (previewRequests.value != requests) {
                    log.d { "attachment preview visibility: ${previewRequests.value.size} -> ${requests.size}" }
                    previewRequests.value = requests
                }
                next
            }
        }
        reflect(machine, onOutput = { output ->
            when (output) {
                is AiStudioOutput.SubmitFailed -> updateState {
                    if (output.submissionId.isEmpty()) {
                        restoreDraft(output, machine.state.value)
                    } else {
                        rejectSubmission(output.submissionId)
                    }
                }

                is AiStudioOutput.SubmitPrepared -> updateState {
                    prepareSubmission(output.submissionId, output.sessionId)
                }

                is AiStudioOutput.SubmitAccepted -> updateState {
                    acceptSubmission(output.submissionId, output.sessionId)
                }

                is AiStudioOutput.SubmitRejected -> updateState {
                    rejectSubmission(output.submissionId, output.sessionId)
                }

                // Delivery results of engine questions are handled by the question bridge.
                is AiStudioOutput.PermissionAnswerFailed, is AiStudioOutput.RunEnded -> Unit
            }
        }) { reflectMachine(it) }
        whileSubscribed(name = "workspace") {
            val pipeline = this
            coroutineScope {
                launch { observeWorkspace(pipeline) }
                launch {
                    entries.showsAttachments.collect { updateState { copy(isAttachmentsEnabled = it) } }
                }
                launch { entries.showsRemember.collect { updateState { copy(isRememberEnabled = it) } } }
                launch { entries.showsOrganism.collect { updateState { copy(isOrganismEnabled = it) } } }
                launch { organisms.observe(pipeline) }
                launch {
                    machines.observe(AttachmentsMachineKey).collectLatest { ref ->
                        ref?.outputs?.collect { output ->
                            when (output) {
                                is AttachmentsOutput.Imported -> appendAttachments(
                                    pipeline,
                                    output.requestId,
                                    output.attachments.map { it.toUi() }.toImmutableList(),
                                )

                                is AttachmentsOutput.Failed, is AttachmentsOutput.Cancelled -> updateState {
                                    val id = if (output is AttachmentsOutput.Failed) {
                                        output.requestId
                                    } else {
                                        (output as AttachmentsOutput.Cancelled).requestId
                                    }
                                    val key = attachmentRequests[id]
                                    val affected = panes.filter { draftKey(it.id) == key }.map { it.id }
                                    copy(
                                        attachmentRequests = (attachmentRequests - id).toImmutableMap(),
                                        attachmentErrorPanes = if (output is AttachmentsOutput.Failed) {
                                            (attachmentErrorPanes + affected).toImmutableSet()
                                        } else {
                                            attachmentErrorPanes
                                        },
                                    )
                                }

                                is AttachmentsOutput.Completed, is AttachmentsOutput.NativeRequested -> Unit
                            }
                        }
                    }
                }
                launch { observeResearch(pipeline) }
                launch { observeComputerUse(pipeline) }
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
                launch { observeAttachmentPreviews(pipeline, previews, previewRequests) }
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

    /** Shows an existing owner's conversation once capture opens; later user navigation remains in control. */
    private suspend fun observeComputerUse(pipeline: StudioPipeline) = with(pipeline) {
        var focusedTarget: Pair<CaptureOwner.Agent, String?>? = null
        val owners = machines.observe(ComputerUseMachineKey).flatMapLatest { ref ->
            ref?.state ?: flowOf(ComputerUseState.Idle)
        }.map { state ->
            val capture = state as? ComputerUseState.Capturing ?: return@map null
            val owner = capture.owner as? CaptureOwner.Agent ?: return@map null
            owner to capture.isOpen
        }.distinctUntilChanged()
        combine(
            owners,
            backend.repository().observeWorkspace(),
            machine.state.map { it is AiStudioState.Ready }.distinctUntilChanged(),
        ) { capture, workspace, isReady ->
            if (capture == null) {
                null
            } else {
                val (owner, isOpen) = capture
                Triple(
                    owner,
                    workspace.sessions.firstOrNull { session -> session.nativeSession == owner.session }?.id,
                    isOpen && isReady,
                )
            }
        }.distinctUntilChanged().collect { target ->
            if (target == null) {
                focusedTarget = null
            } else if (target.third) {
                val next = target.first to target.second
                if (next != focusedTarget) {
                    focusedTarget = next
                    focusComputerUseSession(pipeline, target.second)
                }
            }
        }
    }

    private suspend fun focusComputerUseSession(pipeline: StudioPipeline, sessionId: String?) = with(pipeline) {
        updateState { copy(sidebar = sidebar.copy(isDrawerOpen = false)) }
        if (sessionId == null) return@with
        val ready = machine.state.value as? AiStudioState.Ready ?: return@with
        val shown = ready.panes.firstOrNull { it.sessionId == sessionId }
        val navigation = when {
            shown == null -> AiStudioScreenIntent.OpenSession(sessionId)
            shown.id != ready.focusedPaneId -> AiStudioScreenIntent.FocusPane(shown.id)
            else -> null
        }
        if (navigation != null) navigate(pipeline, navigation)
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
                        repository.observeMessages(id)
                            .flatMapLatest { messages -> messages.map { it.toUi() }.withAttachmentMetadata() }
                            .map { entries -> id to entries }
                    },
                ) { it.toMap() },
            )
        }
    }

    /**
     * Transcript entries with the durable metadata of the attachments they reference.
     *
     * A streamed revision re-projects the entries every coalescing window of the history mirror, while the catalog
     * is asked only when the transcript really references attachments: observing an empty request used to restart a
     * database query — and its log record — on every revision.
     */
    private fun List<MessageUi>.withAttachmentMetadata(): Flow<ImmutableList<MessageUi>> {
        val attachmentIds = asSequence().filterIsInstance<MessageUi.Prompt>()
            .flatMap { it.attachments }.map { AttachmentId(it.id) }.distinct().toList()
        if (attachmentIds.isEmpty()) return flowOf(toImmutableList())
        return attachmentsCatalog.observe(attachmentIds).map { descriptors ->
            val metadata = descriptors.associate { it.id.value to it.toUi() }
            map { message ->
                if (message is MessageUi.Prompt) {
                    message.copy(
                        attachments = message.attachments.map {
                            metadata[it.id] ?: it
                        }.toImmutableList(),
                    )
                } else {
                    message
                }
            }.toImmutableList()
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
            is AiStudioScreenIntent.Organism -> organisms.handle(pipeline, intent)
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
        val previous = machine.state.value
        val result = sendTo(machine, command)
        val replaced = navigationReplacedPanes(intent, previous, machine.state.value)
        if (result == SendResult.Accepted) updateState { afterNavigation(intent, replaced) }
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

            is AiStudioScreenIntent.Submit -> submitStudioDraft(pipeline, machine, intent.paneId)

            is AiStudioScreenIntent.Attachment -> attach(pipeline, intent)

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

    private suspend fun attach(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Attachment) = with(pipeline) {
        log.i { "Attachment action kind=${intent::class.simpleName.orEmpty()}" }
        when (intent) {
            is AiStudioScreenIntent.PickAttachments -> pickAttachments(pipeline, intent.paneId, null)

            is AiStudioScreenIntent.ImportAttachments -> pickAttachments(pipeline, intent.paneId, intent.inputs)

            is AiStudioScreenIntent.RemoveAttachment -> updateState {
                val key = draftKey(intent.paneId)
                copy(
                    draftAttachments = (
                        draftAttachments + (
                            key to attachments(intent.paneId)
                                .filterNot { it.id == intent.id }.toImmutableList()
                        )
                    ).toImmutableMap(),
                )
            }

            is AiStudioScreenIntent.OpenAttachment -> attachmentNavigation.emit(
                StudioAttachmentNavigation.Preview(intent.id),
            )

            is AiStudioScreenIntent.ExportAttachment -> attachmentNavigation.emit(
                StudioAttachmentNavigation.Preview(intent.id, true),
            )

            is AiStudioScreenIntent.AttachmentsSelected -> appendAttachments(pipeline, intent.requestId, intent.files)

            is AiStudioScreenIntent.AttachmentPreviewVisible -> updateState { withPreviewVisibility(intent) }
        }
    }

    private suspend fun pickAttachments(pipeline: StudioPipeline, paneId: Int, inputs: List<NativeAttachmentUi>?) =
        with(
            pipeline,
        ) {
            withState {
                if (!isAttachmentsEnabled) return@withState
                val support = attachmentSupport(paneId) ?: return@withState
                if (support.mediaTypes.isEmpty()) return@withState
                val requestId = Uuid.random().toString()
                val key = draftKey(paneId)
                updateState {
                    copy(
                        attachmentRequests = (
                            attachmentRequests.filterValues {
                                it != key
                            } + (requestId to key)
                        ).toImmutableMap(),
                        attachmentErrorPanes = (attachmentErrorPanes - paneId).toImmutableSet(),
                    )
                }
                if (inputs == null) {
                    log.i { "Open correlated attachment picker" }
                    attachmentNavigation.emit(
                        StudioAttachmentNavigation.Pick(requestId, support.toDomain()),
                    )
                } else {
                    captureAttachments(pipeline, requestId, support, inputs)
                }
            }
        }

    private suspend fun captureAttachments(
        pipeline: StudioPipeline,
        requestId: String,
        support: InputSupportUi,
        inputs: List<NativeAttachmentUi>,
    ) = with(pipeline) {
        val captured = inputs.map {
            when (it) {
                is NativeAttachmentUi.File -> AttachmentInput.File(it.location)
                is NativeAttachmentUi.Image -> AttachmentInput.Bytes("clipboard.png", "image/png", it.bytes)
            }
        }
        val result = machines.send(
            AttachmentsMachineKey,
            AttachmentsIntent.Public.Import(requestId, captured, support.toDomain()),
        )
        if (result != SendResult.Accepted) {
            updateState {
                copy(attachmentRequests = (attachmentRequests - requestId).toImmutableMap())
            }
        }
    }

    private suspend fun appendAttachments(
        pipeline: StudioPipeline,
        requestId: String,
        files: ImmutableList<AttachmentUi>,
    ) = with(
        pipeline,
    ) {
        updateState {
            val key = attachmentRequests[requestId] ?: return@updateState this
            val next = (draftAttachments[key].orEmpty() + files).distinctBy { it.id }.toImmutableList()
            copy(
                draftAttachments = (draftAttachments + (key to next)).toImmutableMap(),
                attachmentRequests = (attachmentRequests - requestId).toImmutableMap(),
            )
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

/** The component translates these requests into feature routes and result contracts. */
sealed interface StudioAttachmentNavigation {
    /** Opens a correlated native file picker. */
    data class Pick(val requestId: String, val support: PromptInputSupport) : StudioAttachmentNavigation

    /** Opens an existing file; optional export starts its native save dialog. */
    data class Preview(val id: String, val isExportRequested: Boolean = false) : StudioAttachmentNavigation
}
