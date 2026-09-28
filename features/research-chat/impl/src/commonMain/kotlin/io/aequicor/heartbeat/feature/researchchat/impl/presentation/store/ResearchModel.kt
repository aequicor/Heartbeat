package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatOutput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.impl.di.scope.ResearchChatScope
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchFileImporter
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce

private typealias ResearchPipeline = PipelineContext<ResearchScreenState, ResearchScreenIntent, ResearchScreenAction>

/** Local input only; every business command is guarded and executed by the research machine. */
@SingleIn(ResearchChatScope::class)
@Inject
internal class ResearchModel(
    private val machine: Machine<ResearchChatState, ResearchChatIntent, ResearchChatOutput>,
    private val importer: ResearchFileImporter,
    route: ResearchChatRoute,
    @ForScope(ResearchChatScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    val store = factory.create<ResearchScreenState, ResearchScreenIntent, ResearchScreenAction>(
        name = "ResearchChat",
        initial = ResearchScreenState(isFileImportAvailable = importer.isAvailable)
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
        scope.coroutineScope.launch { machine.send(ResearchChatIntent.Public.Start(route.target)) }
    }

    private suspend fun handle(pipeline: ResearchPipeline, intent: ResearchScreenIntent) = with(pipeline) {
        when (intent) {
            is ResearchScreenIntent.DraftChanged -> updateState {
                copy(draft = intent.value, drafts = (drafts + (questionId.orEmpty() to intent.value)).toImmutableMap())
            }

            is ResearchScreenIntent.ShowResourceDialog -> updateState { copy(isResourceDialogOpen = intent.isOpen) }

            is ResearchScreenIntent.ResourceEdit -> editResource(pipeline, intent)

            ResearchScreenIntent.ImportFile -> importFile(pipeline)

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

    private suspend fun importFile(pipeline: ResearchPipeline) = with(pipeline) {
        val file = importer.pick() ?: return@with
        updateState {
            copy(
                isResourceDialogOpen = true,
                resourceTitle = file.title,
                resourceKind = file.kind.toUi(),
                resourceValue = file.value,
                resourceMediaType = file.mediaType,
            )
        }
    }
}

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
    -> null
}
