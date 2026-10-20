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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewMonitor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAttachmentPreviews
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toSessionEffort
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.StudioEmbeds
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.StudioInputContexts
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.FeedCollapseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectTrustNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SecurityLevelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.afterNavigation
import io.aequicor.heartbeat.feature.aistudio.screenmodel.dismissNotice
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.pruneNoticeDismissals
import io.aequicor.heartbeat.feature.aistudio.screenmodel.reduce
import io.aequicor.heartbeat.feature.aistudio.screenmodel.revealNotice
import io.aequicor.heartbeat.feature.aistudio.screenmodel.startRename
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineKey
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
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
import kotlinx.coroutines.awaitCancellation
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
import pro.respawn.flowmvi.dsl.collect
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal typealias StudioPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

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
    assists: ComposerAssists,
    previews: StudioAttachmentPreviews,
    private val harnesses: StudioHarnesses = StudioHarnesses.None,
    private val compactions: StudioCompactionPort = StudioCompactionPort.None,
    private val host: StudioScreenHost = StudioScreenHost.Window,
    private val embeddedScreens: StudioEmbeds = StudioEmbeds.None,
    private val surfaceBinding: StudioSurfaceBinding = StudioSurfaceBinding(StudioSurfaces.None, backend, scope),
    facade: EngineFacade,
    safetyReviewMonitor: AgentSafetyReviewMonitor,
    configuration: EngineConfiguration = UnavailableEngineConfiguration(),
    planning: StudioPlanning = StudioPlanning.None,
) {
    private val log = Log.tag("AiStudioModel")
    private val inputContexts = StudioInputContexts()

    /** Enqueues native host provenance with the exact remote input instance. */
    internal fun remoteIntent(intent: AiStudioScreenIntent, environment: StudioChatEnvironment?) {
        if (environment != null) inputContexts.add(intent, environment)
        store.intent(intent)
    }
    private val harnessSelection = StudioHarnessSelection(harnesses, backend)

    /** Navigation is executed by the lifecycle component, never by a retained IO scope. */
    val attachmentNavigation = MutableSharedFlow<StudioAttachmentNavigation>(extraBufferCapacity = 8)
    private val attachmentInputs = StudioAttachmentInputs(machines, attachmentNavigation)
    private val previewRequests = MutableStateFlow<List<ResourceRef>>(emptyList())

    private val organisms = StudioOrganismView(machine, machines, backend) { it.withAttachmentMetadata() }
    private val plans = StudioPlanView(machine, planning)
    private val appUpdate = StudioAppUpdate(machines)
    private val safetyReviews = StudioSafetyReviews(safetyReviewMonitor, entries.showsSafetyReviews)
    private val projectTrust = StudioProjectTrust(configuration, facade)

    /** Late-bound file attach of an accepted suggestion: the store itself is created below. */
    private var attachSuggestedFile: suspend (paneId: Int, location: String) -> Unit = { _, _ -> }

    private val suggestions = StudioSuggestions(
        assists,
        isFileInputAllowed = host == StudioScreenHost.Window,
    ) { paneId, location ->
        attachSuggestedFile(paneId, location)
    }
    val store = factory.create<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>(
        name = "AiStudio",
        initial = AiStudioScreenState(
            now = clock.now(),
            harnessChoices = HarnessChoicesUi(isReady = harnesses === StudioHarnesses.None),
        ).reflectMachine(machine.state.value),
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
                surfaceBinding.onState(next)
                suggestions.onState(next)
            }
        }
        reflect(machine, onOutput = { output ->
            handleStudioSubmissionOutput(this, machine.state.value, harnesses, output)
        }) { reflectMachine(it) }
        init { launch { attachmentInputs.observe(this@init) } }
        install {
            name = "project-trust"
            onState { _, new -> new.also(projectTrust::focus) }
        }
        whileSubscribed(name = "workspace") {
            val pipeline = this
            coroutineScope {
                launch { observeWorkspace(pipeline) }
                launch { observeEnginePreparation(pipeline, facade) }
                launch { embeddedScreens.observe(pipeline) }
                launch {
                    entries.showsAttachments.collect { updateState { copy(isAttachmentsEnabled = it) } }
                }
                launch {
                    entries.securityLevels.collect { level ->
                        updateState { copy(securityLevel = level.toUi()) }
                    }
                }
                launch { entries.showsRemember.collect { updateState { copy(isRememberEnabled = it) } } }
                launch { compactions.availability.collect { updateState { copy(isCompactEnabled = it) } } }
                launch {
                    compactions.compacting.collect { updateState { copy(compactingChats = it.toImmutableSet()) } }
                }
                launch {
                    compactions.feed.collect {
                        updateState {
                            copy(
                                feedCollapse = FeedCollapseUi(it.isReasoningCollapsed, it.isToolCallsCollapsed),
                            )
                        }
                    }
                }
                launch {
                    entries.showsOrganism.collect {
                        updateState {
                            copy(
                                isOrganismEnabled = it && host == StudioScreenHost.Window,
                            )
                        }
                    }
                }
                launch { harnessSelection.observe(pipeline) }
                launch {
                    surfaceBinding.observe(pipeline) { paneId, isCurrent ->
                        submitStudioDraft(pipeline, machine, harnesses, paneId, isCurrent = isCurrent)
                    }
                }
                launch { organisms.observe(pipeline) }
                launch { plans.observe(pipeline) }
                launch { observeResearch(pipeline) }
                if (host == StudioScreenHost.Window) launch { appUpdate.observe(pipeline) }
                launch { safetyReviews.observe(pipeline) }
                launch { projectTrust.observe(pipeline) }
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
                            ).pruneNoticeDismissals()
                        }
                    }
                }
                launch { observeTranscripts(pipeline) }
                launch { observeAttachmentPreviews(pipeline, previews, previewRequests) }
                launch { suggestions.observe(pipeline) }
                launch { observeClock(pipeline) }
            }
        }
        reduce { intent -> handle(this, intent, inputContexts.take(intent)) }
    }

    init {
        attachSuggestedFile = { paneId, location ->
            store.intent(
                AiStudioScreenIntent.ImportAttachments(paneId, listOf(NativeAttachmentUi.File(location))),
            )
        }
        store.start(scope.coroutineScope)
        // A remote screen lives through reconnect grace: its submission outputs must not lose their subscriber.
        if (host == StudioScreenHost.Remote) {
            scope.coroutineScope.launch { store.collect { awaitCancellation() } }
        }
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
        val scheduler = machines.observe(SchedulerMachineKey).flatMapLatest { ref ->
            ref?.state ?: flowOf(SchedulerState.Loading)
        }
        combine(backend.repository().observeWorkspace(), scheduler) { workspace, schedule ->
            workspace to schedule
        }.collect { (workspace, schedule) -> updateState { withWorkspace(workspace, schedule) } }
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
                            .flatMapLatest { messages -> messages.toTranscriptUi().withAttachmentMetadata() }
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

    private suspend fun handle(
        pipeline: StudioPipeline,
        intent: AiStudioScreenIntent,
        environment: StudioChatEnvironment?,
    ) {
        when (intent) {
            is AiStudioScreenIntent.DismissNotice -> pipeline.updateState { dismissNotice(intent) }
            is AiStudioScreenIntent.CardFocusApplied -> pipeline.updateState { withCardFocusApplied(intent) }
            is AiStudioScreenIntent.OpenApp -> surfaceBinding.openApp(pipeline, intent)
            is AiStudioScreenIntent.Navigation -> navigate(pipeline, intent)
            is AiStudioScreenIntent.Composer -> compose(pipeline, intent, environment)
            is AiStudioScreenIntent.DiagramAction -> handleStudioDiagramAction(pipeline, machine, intent, environment)
            is AiStudioScreenIntent.SessionAction -> act(pipeline, intent)
            is AiStudioScreenIntent.Sidebar -> pipeline.updateState { copy(sidebar = sidebar.reduce(intent)) }
            is AiStudioScreenIntent.ChatMode -> chatMode(pipeline, intent)
            is AiStudioScreenIntent.SelectHarness -> harnessSelection.select(pipeline, intent)
            is AiStudioScreenIntent.RemoteApplied -> pipeline.updateState { copy(remoteSeq = intent.seq) }
            is AiStudioScreenIntent.Embed -> embeddedScreens.handle(pipeline, intent, environment)
            AiStudioScreenIntent.InstallAppUpdate -> installAppUpdate()
        }
    }

    private suspend fun chatMode(pipeline: StudioPipeline, intent: AiStudioScreenIntent.ChatMode) = when (intent) {
        is AiStudioScreenIntent.Organism -> organisms.handle(pipeline, intent)
        is AiStudioScreenIntent.Plan -> plans.handle(pipeline, intent)
    }

    private suspend fun installAppUpdate() {
        if (host == StudioScreenHost.Window) appUpdate.install()
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

    private suspend fun usage(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Usage) = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.RefreshUsage -> sendTo(machine, AiStudioIntent.Public.RefreshUsage(intent.modelId))

            is AiStudioScreenIntent.CompactContext -> {
                var chat: String? = null
                withState { chat = panes.firstOrNull { it.id == intent.paneId }?.sessionId }
                val id = chat
                val isQueued = id != null && compactions.request(id, null)
                if (!isQueued) updateState { copy(failedPanes = (failedPanes + intent.paneId).toImmutableSet()) }
            }
        }
    }

    /** A draft typed as `/compact` compacts the chat; anything else is sent as a prompt. */
    private suspend fun submit(pipeline: StudioPipeline, paneId: Int, environment: StudioChatEnvironment?) {
        var isPending = false
        pipeline.withState { isPending = isAttachmentPending(paneId) }
        if (isPending) {
            log.i { "Submission waits for attachment import" }
            return
        }
        if (!compactStudioDraft(
                pipeline,
                compactions,
                paneId,
            )
        ) {
            submitStudioDraft(pipeline, machine, harnesses, paneId, environment)
        }
    }

    private suspend fun compose(
        pipeline: StudioPipeline,
        intent: AiStudioScreenIntent.Composer,
        environment: StudioChatEnvironment?,
    ) = with(
        pipeline,
    ) {
        when (intent) {
            is AiStudioScreenIntent.Worktree -> composeWorktree(pipeline, intent)

            is AiStudioScreenIntent.Usage -> usage(pipeline, intent)

            is AiStudioScreenIntent.Answer -> answer(pipeline, intent)

            is AiStudioScreenIntent.Suggestions -> suggestions.handle(pipeline, intent)

            AiStudioScreenIntent.ReloadHarnesses -> harnessSelection.reload(pipeline)

            is AiStudioScreenIntent.Submit -> submit(pipeline, intent.paneId, environment)

            is AiStudioScreenIntent.Attachment -> attachmentInputs.handle(pipeline, intent)

            is AiStudioScreenIntent.Stop -> stop(pipeline, intent.sessionId)

            is AiStudioScreenIntent.SelectModel -> selectSetting(
                pipeline,
                intent.paneId,
                StudioSettingChange.Model(intent.modelId),
            ) { copy(modelId = intent.modelId) }

            is AiStudioScreenIntent.SelectEffort -> selectEffort(pipeline, intent)

            is AiStudioScreenIntent.SelectEngineEffort -> selectEngineEffort(pipeline, intent)

            is AiStudioScreenIntent.SelectApproval -> {
                log.i { "select approval: ${intent.approval}" }
                val change = StudioSettingChange.Approval(intent.approval.toDomain())
                selectSetting(pipeline, intent.paneId, change) { copy(approval = intent.approval.toDomain()) }
            }
        }
    }

    private suspend fun answer(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Answer) = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.DecideProjectTrust -> {
                var notice: ProjectTrustNoticeUi? = null
                withState { notice = projectTrust }
                this@AiStudioModel.projectTrust.decide(intent, notice)
            }

            is AiStudioScreenIntent.RespondPermission -> sendTo(
                machine,
                AiStudioIntent.Public.RespondPermission(intent.sessionId, intent.requestId, intent.optionId),
            )
        }
    }

    private suspend fun stop(pipeline: StudioPipeline, sessionId: String) = with(pipeline) {
        val result = sendTo(machine, AiStudioIntent.Public.Stop(sessionId))
        if (result == SendResult.Accepted) {
            updateState {
                panes.filter { it.sessionId == sessionId }.fold(this) { state, pane ->
                    state.revealNotice(pane.id, StudioNoticeUi.StopFailed)
                }
            }
        }
    }

    private suspend fun composeWorktree(pipeline: StudioPipeline, intent: AiStudioScreenIntent.Worktree) = with(
        pipeline,
    ) {
        when (intent) {
            is AiStudioScreenIntent.SelectWorktree -> sendTo(
                machine,
                AiStudioIntent.Public.SelectWorktree(intent.paneId, intent.isEnabled, intent.isSandbox),
            )

            is AiStudioScreenIntent.DecideWorktree -> machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.ChooseAction(intent.sessionId, intent.action.toDomain()),
            )

            is AiStudioScreenIntent.RecheckWorktree -> machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.Recheck(intent.sessionId, intent.isResume, intent.isResolvingConflicts),
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

    private suspend fun selectEffort(pipeline: StudioPipeline, intent: AiStudioScreenIntent.SelectEffort) {
        val effort = intent.effort.toDomain().toSessionEffort()
        selectSetting(pipeline, intent.paneId, StudioSettingChange.Effort(effort)) {
            copy(effort = intent.effort.toDomain())
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
            if (organisms[sessionId] != null) {
                isHandled = true
                return@withState
            }
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

private fun StudioSecurityLevel.toUi(): SecurityLevelUi = when (this) {
    StudioSecurityLevel.Ask -> SecurityLevelUi.Ask
    StudioSecurityLevel.Automatic -> SecurityLevelUi.Automatic
    StudioSecurityLevel.Trust -> SecurityLevelUi.Trust
}
