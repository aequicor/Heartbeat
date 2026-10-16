package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PermissionOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PermissionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionWaitUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.pruneHarnessChoices
import io.aequicor.heartbeat.feature.aistudio.screenmodel.pruneNoticeDismissals
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

/** Every machine state maps to a phase: no `else`, so a new machine state breaks the build here. */
internal fun AiStudioScreenState.reflectMachine(machine: AiStudioState): AiStudioScreenState = when (machine) {
    AiStudioState.Idle, AiStudioState.Loading -> copy(phase = StudioPhase.Loading)

    AiStudioState.Disabled -> copy(phase = StudioPhase.Disabled)

    AiStudioState.LoadError -> copy(phase = StudioPhase.Error)

    is AiStudioState.Ready -> copy(
        phase = StudioPhase.Ready,
        contexts = machine.contexts.mapValues { it.value.toUi() }.toImmutableMap(),
        providerUsage = machine.providerUsage.mapValues { it.value.toUi() }.toImmutableMap(),
        isProjectAddingAvailable = machine.isProjectAddingAvailable,
        isWorktreeAvailable = machine.isWorktreeAvailable,
        addingProjectTo = machine.addingProjectTo,
        projectErrorPane = machine.projectErrorPane,
        permissions = machine.permissions.map { request ->
            PermissionUi(
                request.sessionId,
                request.requestId,
                request.title,
                request.options.map { PermissionOptionUi(it.id, it.title) }.toImmutableList(),
                request.description,
            )
        }.toImmutableList(),
        panes = machine.panes.map { it.toUi() }.toImmutableList(),
        cardFocusRequests = cardFocusRequests.filter { (pane, request) ->
            machine.panes.any { it.id == pane && it.sessionId == request.chatId }
        }.toImmutableMap(),
        apps = apps.filter { (pane, app) ->
            machine.panes.any { it.id == pane && it.sessionId == app.sessionId && it.projectId == app.projectId }
        }.toImmutableMap(),
        focusedPaneId = machine.focusedPaneId,
        running = machine.running.toImmutableSet(),
        runStartedAt = machine.runStartedAt.toImmutableMap(),
        stopping = machine.stopping.toImmutableSet(),
        stopFailures = machine.stopFailures.toImmutableSet(),
        uncancellable = machine.uncancellable.toImmutableSet(),
        settings = machine.settings.toUi().copy(engineEfforts = settings.engineEfforts),
        configurations = machine.configurations.mapValues { it.value.toUi() }.toImmutableMap(),
    ).pruneHarnessChoices().pruneNoticeDismissals()
}

/** Pending wakes remain visible until delivery settles, including while the next turn is being admitted. */
internal fun AiStudioScreenState.withWorkspace(
    workspace: StudioWorkspace,
    scheduler: SchedulerState = SchedulerState.Loading,
): AiStudioScreenState = copy(
    projects = workspace.projects.map { it.toUi() }.toImmutableList(),
    sessions = workspace.sessions.map { session ->
        val wakes = (scheduler as? SchedulerState.Ready)?.wakes.orEmpty().filter {
            it.session == session.nativeSession
        }
        session.toUi().copy(
            scheduledWait = when {
                wakes.isEmpty() -> null
                wakes.any { it.request.condition.events.isNotEmpty() } -> SessionWaitUi.WaitingForEvent
                else -> SessionWaitUi.Sleeping
            },
        )
    }.toImmutableList(),
).pruneNoticeDismissals()

/** Outputs can lag behind navigation and state reflection; restore only into the owning new-session draft. */
internal fun AiStudioScreenState.restoreDraft(
    output: AiStudioOutput.SubmitFailed,
    machine: AiStudioState,
): AiStudioScreenState {
    val pane = (machine as? AiStudioState.Ready)?.panes?.firstOrNull { it.id == output.paneId } ?: return this
    if (pane.sessionId != null || pane.isCreating || pane.createRequestId != output.requestId) return this
    val key = "pane:${output.paneId}"
    if (!drafts[key].isNullOrEmpty()) return this
    return copy(
        drafts = (drafts + (key to output.prompt)).toImmutableMap(),
        failedPanes = (failedPanes + output.paneId).toImmutableSet(),
    )
}

internal fun StudioProject.toUi(): ProjectUi = ProjectUi(id, name, environment.toUi(), branch)
internal fun StudioSession.toUi(): SessionUi = SessionUi(
    id, title, projectId, updatedAt, isPinned, isUnread, isArchived, branch,
    modelId = modelId,
    isContinuable = isContinuable,
    isWorktree = isWorktree,
    isOrganism = isOrganism,
    isAwaitingChecklist = isAwaitingChecklist,
    isReady = isReady,
    parentChatId = parentChatId,
    isHelper = isHelper,
    executionWorkspace = treeAccess?.workspace?.value,
)
