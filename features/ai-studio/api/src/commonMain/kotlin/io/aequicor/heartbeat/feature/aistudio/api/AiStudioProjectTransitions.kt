package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.StateBuilder

private typealias ProjectTransitions = StateBuilder<
    AiStudioState,
    AiStudioState.Ready,
    AiStudioIntent,
    AiStudioEffect,
    AiStudioOutput,
>

/** Folder selection belongs to the workspace workflow; its native path stays inside the IO effect. */
internal fun ProjectTransitions.projects() {
    on<AiStudioIntent.Internal.ProjectAvailabilityChanged> {
        stay { state.copy(isProjectAddingAvailable = intent.isAvailable) }
    }
    on<AiStudioIntent.Public.AddProject>(
        guard = {
            state.isProjectAddingAvailable && state.addingProjectTo == null &&
                state.panes.any { it.isProjectDraft(intent.paneId) }
        },
    ) {
        stay { state.copy(addingProjectTo = intent.paneId, projectErrorPane = null) }
        effect { AiStudioEffect.ChooseProject(intent.paneId) }
    }
    on<AiStudioIntent.Internal.ProjectChosen>(guard = { state.addingProjectTo == intent.paneId }) {
        stay {
            state.copy(
                addingProjectTo = null,
                projectErrorPane = null,
                panes = state.panes.map {
                    if (it.isProjectDraft(intent.paneId) && intent.projectId != null) {
                        it.copy(projectId = intent.projectId)
                    } else {
                        it
                    }
                },
            )
        }
    }
    on<AiStudioIntent.Internal.ProjectChoiceFailed>(guard = { state.addingProjectTo == intent.paneId }) {
        stay { state.copy(addingProjectTo = null, projectErrorPane = intent.paneId) }
    }
}

private fun StudioPane.isProjectDraft(paneId: Int): Boolean = id == paneId && sessionId == null && !isCreating
