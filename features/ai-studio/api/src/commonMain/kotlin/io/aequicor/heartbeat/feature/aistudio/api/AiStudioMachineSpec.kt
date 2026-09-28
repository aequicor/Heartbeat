package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec

/** Most panes shown side by side; compact layouts show only the focused one. */
public const val MAX_STUDIO_PANES: Int = 2

/**
 * Studio workflow.
 *
 * | From | Intent | Guard | To | Effect / Output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | enabled | Ready (one new-session pane) | ObserveAvailability, ObserveRuntime, ObserveModels |
 * | Loading | Loaded | disabled | Disabled | ObserveAvailability |
 * | Loading | LoadFailed | | LoadError | |
 * | LoadError | Retry | | Loading | Load |
 * | Ready | AvailabilityChanged | disabled | Disabled | ObserveAvailability |
 * | Disabled | AvailabilityChanged | enabled | Loading | Load |
 * | Ready | NewSession | pane open | Ready (pane → new-session page, focused) | |
 * | Ready | SelectProject | new-session page, not creating | Ready (target project) | |
 * | Ready | OpenSession | pane open | Ready (session shown or its pane focused) | Apply(SetUnread(false)) |
 * | Ready | OpenBeside | | Ready (second pane, focused) | Apply(SetUnread(false)) for sessions |
 * | Ready | ClosePane | several panes | Ready (pane removed) | |
 * | Ready | FocusPane | another open pane | Ready | |
 * | Ready | UpdateSettings | | Ready | |
 * | Ready | Submit | prompt, new-session page, not creating | Ready (pane creating) | CreateSession |
 * | Ready | Submit | prompt, session idle | Ready (session running) | Run |
 * | Ready | SessionCreated | | Ready (pane shows session, running) | Run |
 * | Ready | CreateFailed | | Ready (pane not creating) | output SubmitFailed |
 * | Ready | Stop | running, not stopping | Ready (stopping) | Cancel |
 * | Ready | RunFinished | | Ready (idle unless the latest snapshot runs it) | Apply(SetUnread(true)) if hidden |
 * | Ready | RuntimeChanged | | Ready (profile execution snapshot, answered permissions hidden) | |
 * | Ready | RuntimeLost | | Ready (nothing running, stopping or awaiting permission) | |
 * | Ready | RespondPermission | pending, not answered | Ready (permission answered) | RespondPermission |
 * | Ready | ModelsChanged | no model chosen, models offered | Ready (first model chosen) | |
 * | Ready | CancelFailed | | Ready (stop can be retried) | |
 * | Ready | Edit | valid edit | Ready (archived session leaves panes) | Apply |
 *
 * Runs are effects of Ready and continue across every Ready update; several sessions may run at once.
 * Switching the workspace toggle off detaches effects; accepted native turns remain owned by the profile.
 * Effect failures: Load → LoadFailed, CreateSession → CreateFailed, Run → RunFinished(Failed),
 * Cancel → CancelFailed, ObserveRuntime → RuntimeLost; failed Apply, RespondPermission, ObserveModels and
 * ObserveAvailability are only logged. The workspace data lives outside the machine: a restarted
 * process restores stored chats while the transient pane machine starts afresh.
 */
public val AiStudioMachineSpec: MachineSpec<AiStudioState, AiStudioIntent, AiStudioEffect, AiStudioOutput> =
    machineSpec(AiStudioMachineKey, AiStudioState.Idle) {
        state<AiStudioState.Idle> {
            on<AiStudioIntent.Public.Start> {
                goto<AiStudioState.Loading> { AiStudioState.Loading }
                effect { AiStudioEffect.Load }
            }
        }
        state<AiStudioState.Loading> {
            on<AiStudioIntent.Internal.Loaded>(guard = { intent.isEnabled }) {
                goto<AiStudioState.Ready> { initialWorkspace(intent.defaults) }
                effect { AiStudioEffect.ObserveAvailability }
                effect { AiStudioEffect.ObserveRuntime }
                effect { AiStudioEffect.ObserveModels }
            }
            on<AiStudioIntent.Internal.Loaded>(guard = { !intent.isEnabled }) {
                goto<AiStudioState.Disabled> { AiStudioState.Disabled }
                effect { AiStudioEffect.ObserveAvailability }
            }
            on<AiStudioIntent.Internal.LoadFailed> { goto<AiStudioState.LoadError> { AiStudioState.LoadError } }
        }
        state<AiStudioState.LoadError> {
            on<AiStudioIntent.Public.Retry> {
                goto<AiStudioState.Loading> { AiStudioState.Loading }
                effect { AiStudioEffect.Load }
            }
        }
        state<AiStudioState.Disabled> {
            on<AiStudioIntent.Internal.AvailabilityChanged>(guard = { intent.isEnabled }) {
                goto<AiStudioState.Loading> { AiStudioState.Loading }
                effect { AiStudioEffect.Load }
            }
            // The observation first reports the current value; an unchanged value is accepted without a change.
            on<AiStudioIntent.Internal.AvailabilityChanged>(guard = { !intent.isEnabled })
        }
        state<AiStudioState.Ready> {
            runtime()
            navigation()
            conversations()
            executions()
            on<AiStudioIntent.Internal.AvailabilityChanged>(guard = { !intent.isEnabled }) {
                goto<AiStudioState.Disabled> { AiStudioState.Disabled }
                effect { AiStudioEffect.ObserveAvailability }
            }
            on<AiStudioIntent.Internal.AvailabilityChanged>(guard = { intent.isEnabled })
            on<AiStudioIntent.Public.UpdateSettings> { stay { state.copy(settings = intent.settings) } }
            on<AiStudioIntent.Public.Edit>(guard = { intent.edit.isValid() }) {
                stay { if (intent.edit.isArchiving()) state.leave(intent.sessionId) else state }
                effect { AiStudioEffect.Apply(intent.sessionId, intent.edit.normalized()) }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                AiStudioEffect.Load -> AiStudioIntent.Internal.LoadFailed

                is AiStudioEffect.CreateSession -> AiStudioIntent.Internal.CreateFailed(effect.paneId, effect.prompt)

                is AiStudioEffect.Run -> AiStudioIntent.Internal.RunFinished(effect.sessionId, RunOutcome.Failed)

                is AiStudioEffect.Cancel -> AiStudioIntent.Internal.CancelFailed(effect.sessionId)

                AiStudioEffect.ObserveRuntime -> AiStudioIntent.Internal.RuntimeLost

                AiStudioEffect.ObserveModels, is AiStudioEffect.RespondPermission,
                AiStudioEffect.ObserveAvailability, is AiStudioEffect.Apply,
                -> null
            }
        }
    }

private typealias ReadyTransitions = StateBuilder<
    AiStudioState,
    AiStudioState.Ready,
    AiStudioIntent,
    AiStudioEffect,
    AiStudioOutput,
>

private fun ReadyTransitions.navigation() {
    on<AiStudioIntent.Public.NewSession>(guard = { state.hasPane(intent.paneId) }) {
        stay {
            val target = intent.paneId ?: state.focusedPaneId
            state.replacePane(target) { StudioPane(it.id, projectId = intent.projectId) }
        }
    }
    on<AiStudioIntent.Public.SelectProject>(guard = { state.pane(intent.paneId)?.isNewSessionPage() == true }) {
        stay { state.replacePane(intent.paneId) { it.copy(projectId = intent.projectId) } }
    }
    on<AiStudioIntent.Public.OpenSession>(guard = { state.hasPane(intent.paneId) }) {
        stay { state.open(intent.sessionId, intent.paneId ?: state.focusedPaneId) }
        effect { AiStudioEffect.Apply(intent.sessionId, SessionEdit.SetUnread(false)) }
    }
    on<AiStudioIntent.Public.OpenBeside> {
        stay { state.openBeside(intent.sessionId) }
        effect { intent.sessionId?.let { AiStudioEffect.Apply(it, SessionEdit.SetUnread(false)) } }
    }
    on<AiStudioIntent.Public.ClosePane>(guard = { state.panes.size > 1 && state.pane(intent.paneId) != null }) {
        stay {
            val remaining = state.panes.filterNot { it.id == intent.paneId }
            val focused = if (state.focusedPaneId == intent.paneId) remaining.first().id else state.focusedPaneId
            state.copy(panes = remaining, focusedPaneId = focused)
        }
    }
    on<AiStudioIntent.Public.FocusPane>(
        guard = { intent.paneId != state.focusedPaneId && state.pane(intent.paneId) != null },
    ) {
        stay { state.copy(focusedPaneId = intent.paneId) }
    }
}

private fun ReadyTransitions.conversations() {
    on<AiStudioIntent.Public.Submit>(
        guard = { intent.prompt.isNotBlank() && state.pane(intent.paneId)?.isNewSessionPage() == true },
    ) {
        stay { state.replacePane(intent.paneId) { it.copy(isCreating = true) } }
        effect {
            AiStudioEffect.CreateSession(
                intent.paneId,
                state.pane(intent.paneId)?.projectId,
                intent.prompt.trim(),
                state.settings,
            )
        }
    }
    on<AiStudioIntent.Public.Submit>(
        guard = { intent.prompt.isNotBlank() && state.pane(intent.paneId)?.sessionId?.let(state::isIdle) == true },
    ) {
        stay { state.copy(running = state.running + state.sessionOf(intent.paneId)) }
        effect { AiStudioEffect.Run(state.sessionOf(intent.paneId), intent.prompt.trim(), state.settings) }
    }
    on<AiStudioIntent.Internal.SessionCreated> {
        stay {
            state.copy(
                panes = state.panes.map { pane ->
                    if (pane.id == intent.paneId && pane.isCreating) {
                        StudioPane(pane.id, sessionId = intent.sessionId)
                    } else {
                        pane
                    }
                },
                running = state.running + intent.sessionId,
            )
        }
        effect { AiStudioEffect.Run(intent.sessionId, intent.prompt, intent.settings) }
    }
    on<AiStudioIntent.Internal.CreateFailed> {
        stay { state.updatePane(intent.paneId) { it.copy(isCreating = false) } }
        output { AiStudioOutput.SubmitFailed(intent.paneId, intent.prompt) }
    }
}

private fun ReadyTransitions.executions() {
    on<AiStudioIntent.Public.Stop>(
        guard = {
            intent.sessionId in state.running && intent.sessionId !in state.stopping &&
                intent.sessionId !in state.uncancellable
        },
    ) {
        stay {
            state.copy(
                stopping = state.stopping + intent.sessionId,
                stopFailures = state.stopFailures - intent.sessionId,
            )
        }
        effect { AiStudioEffect.Cancel(intent.sessionId) }
    }
    on<AiStudioIntent.Internal.CancelFailed> {
        stay {
            state.copy(
                stopping = state.stopping - intent.sessionId,
                stopFailures = state.stopFailures + intent.sessionId,
            )
        }
    }
    on<AiStudioIntent.Internal.RunFinished> {
        // The latest runtime snapshot wins: an older effect result must not clear a newer native run.
        stay {
            if (intent.sessionId in state.observedRunning) {
                state
            } else {
                state.copy(
                    running = state.running - intent.sessionId,
                    stopping = state.stopping - intent.sessionId,
                )
            }
        }
        effect {
            val isShown = state.panes.any { it.sessionId == intent.sessionId }
            if (isShown) null else AiStudioEffect.Apply(intent.sessionId, SessionEdit.SetUnread(true))
        }
    }
}

private fun ReadyTransitions.runtime() {
    on<AiStudioIntent.Internal.RuntimeChanged> {
        stay {
            val snapshot = intent.snapshot
            val answered = state.answeredPermissions.intersect(snapshot.permissions.map { it.requestId }.toSet())
            state.copy(
                running = snapshot.running,
                observedRunning = snapshot.running,
                stopping = state.stopping.intersect(snapshot.running) - snapshot.stopFailures,
                permissions = snapshot.permissions.filterNot { it.requestId in answered },
                answeredPermissions = answered,
                uncancellable = snapshot.uncancellable,
                stopFailures = (state.stopFailures + snapshot.stopFailures).intersect(snapshot.running),
            )
        }
    }
    on<AiStudioIntent.Internal.RuntimeLost> {
        stay {
            state.copy(
                running = emptySet(),
                observedRunning = emptySet(),
                stopping = emptySet(),
                stopFailures = emptySet(),
                permissions = emptyList(),
                answeredPermissions = emptySet(),
            )
        }
    }
    on<AiStudioIntent.Public.RespondPermission>(guard = {
        intent.requestId !in state.answeredPermissions && state.permissions.any {
            it.sessionId == intent.sessionId && it.requestId == intent.requestId &&
                it.options.any { option -> option.id == intent.optionId }
        }
    }) {
        stay {
            state.copy(
                permissions = state.permissions.filterNot { it.requestId == intent.requestId },
                answeredPermissions = state.answeredPermissions + intent.requestId,
            )
        }
        effect { AiStudioEffect.RespondPermission(intent.sessionId, intent.requestId, intent.optionId) }
    }
    on<AiStudioIntent.Internal.ModelsChanged>(
        guard = { state.settings.modelId.isBlank() && intent.modelIds.isNotEmpty() },
    ) {
        stay { state.copy(settings = state.settings.copy(modelId = intent.modelIds.first())) }
    }
    // A chosen model stays chosen; an empty offer changes nothing.
    on<AiStudioIntent.Internal.ModelsChanged>(
        guard = { state.settings.modelId.isNotBlank() || intent.modelIds.isEmpty() },
    )
}

private fun initialWorkspace(defaults: StudioDefaults): AiStudioState.Ready = AiStudioState.Ready(
    panes = listOf(StudioPane(id = 0, projectId = defaults.projectId)),
    focusedPaneId = 0,
    settings = defaults.settings,
    defaultProjectId = defaults.projectId,
)

private fun AiStudioState.Ready.pane(id: Int): StudioPane? = panes.firstOrNull { it.id == id }

private fun AiStudioState.Ready.hasPane(id: Int?): Boolean = id == null || pane(id) != null

private fun AiStudioState.Ready.isIdle(sessionId: String): Boolean = sessionId !in running

private fun AiStudioState.Ready.sessionOf(paneId: Int): String = checkNotNull(pane(paneId)?.sessionId)

private fun StudioPane.isNewSessionPage(): Boolean = sessionId == null && !isCreating

private fun AiStudioState.Ready.updatePane(id: Int, update: (StudioPane) -> StudioPane): AiStudioState.Ready =
    copy(panes = panes.map { if (it.id == id) update(it) else it })

/** Updates the pane and moves the focus to it. */
private fun AiStudioState.Ready.replacePane(id: Int, update: (StudioPane) -> StudioPane): AiStudioState.Ready =
    updatePane(id, update).copy(focusedPaneId = id)

private fun AiStudioState.Ready.open(sessionId: String, paneId: Int): AiStudioState.Ready {
    val shown = panes.firstOrNull { it.sessionId == sessionId }
    return if (shown != null) {
        copy(focusedPaneId = shown.id)
    } else {
        replacePane(paneId) { StudioPane(it.id, sessionId = sessionId) }
    }
}

private fun AiStudioState.Ready.openBeside(sessionId: String?): AiStudioState.Ready {
    val shown = sessionId?.let { id -> panes.firstOrNull { it.sessionId == id } }
    if (shown != null) return copy(focusedPaneId = shown.id)
    val projectId = if (sessionId == null) pane(focusedPaneId)?.projectId ?: defaultProjectId else null
    val content = StudioPane(id = 0, sessionId = sessionId, projectId = projectId)
    return if (panes.size < MAX_STUDIO_PANES) {
        val id = panes.maxOf { it.id } + 1
        copy(panes = panes + content.copy(id = id), focusedPaneId = id)
    } else {
        val target = panes.first { it.id != focusedPaneId }.id
        replacePane(target) { content.copy(id = it.id) }
    }
}

/** Archived sessions leave their panes; the panes return to the new-session page of the default project. */
private fun AiStudioState.Ready.leave(sessionId: String): AiStudioState.Ready = copy(
    panes = panes.map { if (it.sessionId == sessionId) StudioPane(it.id, projectId = defaultProjectId) else it },
)

private fun SessionEdit.isValid(): Boolean = this !is SessionEdit.Rename || title.isNotBlank()

private fun SessionEdit.isArchiving(): Boolean = this is SessionEdit.SetArchived && isArchived

private fun SessionEdit.normalized(): SessionEdit = if (this is SessionEdit.Rename) copy(title = title.trim()) else this
