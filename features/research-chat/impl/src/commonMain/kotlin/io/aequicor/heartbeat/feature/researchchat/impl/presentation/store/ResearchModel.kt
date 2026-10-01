package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatOutput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.impl.di.scope.ResearchChatScope
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachmentAccess
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchThumbnailEncoder
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import kotlin.uuid.Uuid

private typealias ResearchPipeline = PipelineContext<ResearchScreenState, ResearchScreenIntent, ResearchScreenAction>

/** Local input only; every business command is guarded and executed by the research machine. */
@SingleIn(ResearchChatScope::class)
@Inject
@OptIn(ExperimentalCoroutinesApi::class)
internal class ResearchModel(
    private val machine: Machine<ResearchChatState, ResearchChatIntent, ResearchChatOutput>,
    route: ResearchChatRoute,
    @ForScope(ResearchChatScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
    private val attachments: ResearchAttachments = ResearchAttachments.Legacy,
    private val attachmentAccess: ResearchAttachmentAccess = ResearchAttachmentAccess.Disabled,
    resolver: ResourceResolver? = null,
    dispatchers: DispatcherProvider? = null,
    encoder: ResearchThumbnailEncoder? = null,
) {
    private val thumbnails = ResearchThumbnails(scope.coroutineScope, resolver, dispatchers, encoder)
    private val pendingPicks = mutableMapOf<String, PendingResearchPick>()

    val store = factory.create<ResearchScreenState, ResearchScreenIntent, ResearchScreenAction>(
        name = "ResearchChat",
        initial = ResearchScreenState()
            .reflectResearch(machine.state.value),
        onError = { copy(hasError = true) },
    ) {
        reflect(machine, onOutput = { output ->
            when (output) {
                is ResearchChatOutput.Submitted -> updateState {
                    val submitted = submittedDrafts[output.questionId]
                    val isUnchangedDraft = submitted != null && drafts[output.questionId] == submitted
                    copy(
                        drafts = if (isUnchangedDraft) (drafts - output.questionId).toImmutableMap() else drafts,
                        draft = if (isUnchangedDraft && questionId == output.questionId) "" else draft,
                        submittedDrafts = (submittedDrafts - output.questionId).toImmutableMap(),
                    )
                }

                ResearchChatOutput.ResourceAdded -> updateState {
                    copy(isResourceDialogOpen = false, resourceTitle = "", resourceValue = "", resourceMediaType = null)
                }
            }
        }) { reflectResearch(it) }
        reduce { handle(this, it) }
    }

    init {
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch {
            thumbnails.state.collect { store.intent(ResearchScreenIntent.ThumbnailsChanged(it)) }
        }
        scope.coroutineScope.launch { machine.send(ResearchChatIntent.Public.Start(route.target)) }
        scope.coroutineScope.launch {
            machine.state.map { (it as? ResearchChatState.Ready)?.session?.target }.filterNotNull()
                .distinctUntilChanged().flatMapLatest(attachmentAccess::observe).collect {
                    store.intent(ResearchScreenIntent.AttachmentPolicyChanged(it))
                }
        }
    }

    private suspend fun handle(pipeline: ResearchPipeline, intent: ResearchScreenIntent) = with(pipeline) {
        when (intent) {
            is ResearchScreenIntent.DraftChanged -> updateState {
                copy(draft = intent.value, drafts = (drafts + (questionId.orEmpty() to intent.value)).toImmutableMap())
            }

            is ResearchScreenIntent.ShowResourceDialog -> updateState { copy(isResourceDialogOpen = intent.isOpen) }

            is ResearchScreenIntent.ResourceEdit -> editResource(pipeline, intent)

            ResearchScreenIntent.ImportFile -> pickFiles(pipeline)

            is ResearchScreenIntent.AttachmentEdit -> editAttachment(pipeline, intent)

            ResearchScreenIntent.Submit -> submit(pipeline)

            ResearchScreenIntent.AddResource -> withState {
                sendTo(
                    machine,
                    ResearchChatIntent.Public.AddResource(
                        resourceKind.toDomain(),
                        resourceTitle,
                        resourceValue,
                        resourceScope.toDomain(),
                        resourceMediaType,
                    ),
                )
            }

            ResearchScreenIntent.DismissError -> {
                updateState { copy(hasError = false) }
                sendTo(machine, ResearchChatIntent.Public.DismissError)
            }

            ResearchScreenIntent.Retry, ResearchScreenIntent.NewSession, is ResearchScreenIntent.SelectSession,
            ResearchScreenIntent.NewQuestion, is ResearchScreenIntent.SelectQuestion, ResearchScreenIntent.Stop,
            is ResearchScreenIntent.SetResourceSelected, is ResearchScreenIntent.ShareResource,
            is ResearchScreenIntent.RemoveResource,
            -> {
                val command = command(intent)
                if (command != null) sendTo(machine, command)
            }
        }
    }

    private suspend fun editAttachment(pipeline: ResearchPipeline, intent: ResearchScreenIntent.AttachmentEdit) =
        with(pipeline) {
            when (intent) {
                is ResearchScreenIntent.FilesPicked -> addPicked(pipeline, intent.requestId, intent.attachments)

                is ResearchScreenIntent.DroppedFiles -> importInputs(
                    pipeline,
                    intent.paths.map { AttachmentInput.File(it) },
                )

                is ResearchScreenIntent.PastedImage -> importInputs(
                    pipeline,
                    listOf(
                        AttachmentInput.Bytes("clipboard.png", "image/png", intent.bytes),
                    ),
                )

                is ResearchScreenIntent.AttachmentPolicyChanged -> updateState {
                    copy(isFileImportAvailable = intent.policy.isEnabled, attachmentSupport = intent.policy.support)
                        .reflectResearch(machine.state.value)
                }

                is ResearchScreenIntent.OpenAttachment -> action(ResearchScreenAction.OpenAttachment(intent.id))

                is ResearchScreenIntent.SaveAttachment -> action(ResearchScreenAction.SaveAttachment(intent.id))

                is ResearchScreenIntent.LoadThumbnail -> thumbnails.load(
                    intent.key,
                    ResourceRef("attachment:${intent.id}", intent.mime),
                )

                is ResearchScreenIntent.ReleaseThumbnail -> thumbnails.release(intent.key)

                is ResearchScreenIntent.ThumbnailsChanged -> updateState {
                    copy(
                        thumbnails = intent.values.toImmutableMap(),
                    )
                }
            }
        }

    /** Capture input before dispatch so a late acknowledgement never erases edits made after sending. */
    private suspend fun submit(pipeline: ResearchPipeline): Unit = with(pipeline) {
        var submission: Triple<String?, String, String?>? = null
        withState { submission = submissionSnapshot() }
        val (id, prompt, previousPrompt) = submission ?: return@with
        if (id != null) {
            updateState { copy(submittedDrafts = (submittedDrafts + (id to prompt)).toImmutableMap()) }
        }
        sendTo(machine, ResearchChatIntent.Public.Submit(prompt)) {
            if (id != null) {
                updateState {
                    val remaining = submittedDrafts - id
                    copy(
                        submittedDrafts = if (previousPrompt == null) {
                            remaining.toImmutableMap()
                        } else {
                            (remaining + (id to previousPrompt)).toImmutableMap()
                        },
                    )
                }
            }
        }
    }

    private suspend fun editResource(pipeline: ResearchPipeline, intent: ResearchScreenIntent.ResourceEdit) =
        with(pipeline) {
            when (intent) {
                is ResearchScreenIntent.ResourceKindChanged -> updateState {
                    copy(resourceKind = intent.kind, resourceValue = "", resourceMediaType = null)
                }

                is ResearchScreenIntent.ResourceScopeChanged -> updateState { copy(resourceScope = intent.scope) }

                is ResearchScreenIntent.ResourceTitleChanged -> updateState { copy(resourceTitle = intent.value) }

                is ResearchScreenIntent.ResourceValueChanged -> updateState {
                    copy(
                        resourceValue = intent.value,
                        resourceMediaType = null,
                    )
                }

                is ResearchScreenIntent.SelectSourceScope -> updateState { copy(selectedSourceScope = intent.scope) }
            }
        }

    private suspend fun pickFiles(pipeline: ResearchPipeline) = with(pipeline) {
        val request = Uuid.random().toString()
        val pending = pendingPick(pipeline) ?: return@with
        pendingPicks.clear()
        pendingPicks[request] = pending
        withState { action(ResearchScreenAction.PickFiles(request, attachmentSupport)) }
    }

    private suspend fun pendingPick(pipeline: ResearchPipeline): PendingResearchPick? = with(pipeline) {
        val state = machine.state.value as? ResearchChatState.Ready ?: return@with null
        var scope = ResearchResourceScope.Question
        var isAvailable = false
        withState {
            scope = resourceScope.toDomain()
            isAvailable = isEditable && isFileImportAvailable
        }
        val sessionId = state.sessionId
        val questionId = state.questionId
        return@with if (isAvailable && sessionId != null && questionId != null) {
            PendingResearchPick(sessionId, questionId, scope)
        } else {
            null
        }
    }

    private suspend fun importInputs(pipeline: ResearchPipeline, inputs: List<AttachmentInput>) = with(pipeline) {
        val pending = pendingPick(pipeline) ?: return@with
        var files = emptyList<AttachmentDescriptor>()
        withState { files = attachments.import(inputs, attachmentSupport) }
        addFiles(pipeline, pending, files)
    }

    private suspend fun addPicked(pipeline: ResearchPipeline, request: String, files: List<AttachmentDescriptor>) =
        with(pipeline) {
            val pending = pendingPicks.remove(request) ?: return@with
            addFiles(pipeline, pending, files)
        }

    private suspend fun addFiles(
        pipeline: ResearchPipeline,
        pending: PendingResearchPick,
        files: List<AttachmentDescriptor>,
    ) = with(
        pipeline,
    ) {
        if (files.isEmpty()) return@with
        sendTo(
            machine,
            ResearchChatIntent.Public.AddAttachments(
                pending.sessionId,
                pending.questionId,
                files,
                pending.scope,
            ),
        )
    }
}

private data class PendingResearchPick(val sessionId: String, val questionId: String, val scope: ResearchResourceScope)

private fun ResearchScreenState.submissionSnapshot(): Triple<String?, String, String?> =
    Triple(questionId, draft, questionId?.let { submittedDrafts[it] })

private fun command(intent: ResearchScreenIntent): ResearchChatIntent.Public? = when (intent) {
    ResearchScreenIntent.Retry -> ResearchChatIntent.Public.Retry

    ResearchScreenIntent.NewSession -> ResearchChatIntent.Public.NewSession

    is ResearchScreenIntent.SelectSession -> ResearchChatIntent.Public.SelectSession(intent.id)

    ResearchScreenIntent.NewQuestion -> ResearchChatIntent.Public.NewQuestion

    is ResearchScreenIntent.SelectQuestion -> ResearchChatIntent.Public.SelectQuestion(intent.id)

    ResearchScreenIntent.Stop -> ResearchChatIntent.Public.Stop

    is ResearchScreenIntent.SetResourceSelected -> ResearchChatIntent.Public.SetResourceSelected(
        intent.id,
        intent.isSelected,
    )

    is ResearchScreenIntent.ShareResource -> ResearchChatIntent.Public.ShareResource(intent.id)

    is ResearchScreenIntent.RemoveResource -> ResearchChatIntent.Public.RemoveResource(intent.id)

    is ResearchScreenIntent.DraftChanged, is ResearchScreenIntent.ShowResourceDialog,
    is ResearchScreenIntent.ResourceKindChanged, is ResearchScreenIntent.ResourceScopeChanged,
    is ResearchScreenIntent.ResourceTitleChanged, is ResearchScreenIntent.ResourceValueChanged,
    is ResearchScreenIntent.SelectSourceScope, ResearchScreenIntent.ImportFile, ResearchScreenIntent.Submit,
    ResearchScreenIntent.AddResource, ResearchScreenIntent.DismissError,
    is ResearchScreenIntent.FilesPicked, is ResearchScreenIntent.DroppedFiles, is ResearchScreenIntent.PastedImage,
    is ResearchScreenIntent.AttachmentPolicyChanged, is ResearchScreenIntent.OpenAttachment,
    is ResearchScreenIntent.SaveAttachment, is ResearchScreenIntent.LoadThumbnail,
    is ResearchScreenIntent.ReleaseThumbnail, is ResearchScreenIntent.ThumbnailsChanged,
    -> null
}
