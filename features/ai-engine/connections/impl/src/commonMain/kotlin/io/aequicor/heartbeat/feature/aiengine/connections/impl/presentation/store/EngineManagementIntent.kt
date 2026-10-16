package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode

/** Management of the focused engine: requests the machine executes and edits of local input. */
sealed interface EngineManagementIntent : EngineConnectionsScreenIntent {
    /** A request the machine executes as an engine management command. */
    sealed interface EngineRequest : EngineManagementIntent

    /** An edit of local input (a launch draft, a sign-in code, a pending confirmation). */
    sealed interface EngineInput : EngineManagementIntent

    /** Switches the focused engine on or off in this profile. */
    data class SetEngineEnabled(val isEnabled: Boolean) : EngineRequest

    /** Inspects the installation and the CLI login. */
    data object InspectEngine : EngineRequest

    /** Looks up the newest release. */
    data object CheckUpdates : EngineRequest

    /** Stops idle runtimes so new sessions start with the current settings. */
    data object RestartEngine : EngineRequest

    /** Starts [action]; removals, unverified updates and sign-out are confirmed first. */
    data class RequestEngineAction(val action: EngineActionUi, val method: LoginMethodUi = LoginMethodUi.Browser) :
        EngineRequest

    /** Confirms the action waiting for confirmation. */
    data object ConfirmEngineAction : EngineRequest

    /** Keeps things as they are. */
    data object DismissEngineAction : EngineInput

    /** Cancels the running job. */
    data object CancelEngineJob : EngineRequest

    /** Hides the outcome of the last job. */
    data object DismissEngineJob : EngineRequest

    /** Replaces the launch settings draft. */
    data class EditLaunch(val draft: LaunchDraftUi) : EngineInput

    /** Saves the draft when it has no errors. */
    data object SaveLaunch : EngineRequest

    /** Drops the draft and shows the saved settings. */
    data object DiscardLaunch : EngineInput

    /** Starts a draft with the engine's defaults; it applies once saved. */
    data object ResetLaunch : EngineInput

    /** Edits the code pasted from a sign-in page. */
    data class EditLoginCode(val code: String) : EngineInput {
        override fun toString(): String = "EditLoginCode(***)"
    }

    /** Passes the pasted code to the signing-in CLI. */
    data object SubmitLoginCode : EngineRequest
}

/** Removals, an update replacing the verified bundled copy and sign-out ask first. */
internal fun EngineConnectionsScreenState.needsConfirmation(action: EngineActionUi): Boolean = when (action) {
    EngineActionUi.Uninstall, EngineActionUi.Logout -> true
    EngineActionUi.Update -> panel?.installation?.support == InstallSupportUi.Bundled
    EngineActionUi.Install, EngineActionUi.Login, EngineActionUi.SyncPackages, EngineActionUi.UpdatePackages -> false
}

/** Whether [intent] edits the launch draft, which changes the panel's validation. */
internal val EngineManagementIntent.EngineInput.isLaunchEdit: Boolean
    get() = this is EngineManagementIntent.EditLaunch || this == EngineManagementIntent.DiscardLaunch ||
        this == EngineManagementIntent.ResetLaunch

/** The local input after [intent]. */
internal fun EngineConnectionsScreenState.edit(intent: EngineManagementIntent.EngineInput) = when (intent) {
    is EngineManagementIntent.EditLaunch -> copy(launchDraft = intent.draft)
    EngineManagementIntent.DiscardLaunch -> copy(launchDraft = null)
    EngineManagementIntent.ResetLaunch -> copy(launchDraft = LaunchDraftUi())
    is EngineManagementIntent.EditLoginCode -> copy(loginCode = intent.code)
    EngineManagementIntent.DismissEngineAction -> copy(confirmAction = null)
}

/** The local input once the machine accepted [intent]: what it sent is cleared. */
internal fun EngineConnectionsScreenState.sent(intent: EngineManagementIntent.EngineRequest) = when (intent) {
    EngineManagementIntent.SaveLaunch -> copy(launchDraft = null)

    EngineManagementIntent.SubmitLoginCode -> copy(loginCode = "")

    EngineManagementIntent.ConfirmEngineAction -> copy(confirmAction = null)

    is EngineManagementIntent.SetEngineEnabled, EngineManagementIntent.InspectEngine,
    EngineManagementIntent.CheckUpdates, EngineManagementIntent.RestartEngine,
    is EngineManagementIntent.RequestEngineAction, EngineManagementIntent.CancelEngineJob,
    EngineManagementIntent.DismissEngineJob,
    -> this
}

/** The management command of an engine request, or null when the panel's state does not allow it. */
internal fun EngineConnectionsScreenState.commandFor(intent: EngineManagementIntent.EngineRequest): EngineCommand? {
    if (panel == null) return null
    return when (intent) {
        is EngineManagementIntent.SetEngineEnabled -> EngineCommand.SetEnabled(intent.isEnabled)
        EngineManagementIntent.InspectEngine -> EngineCommand.Inspect
        EngineManagementIntent.CheckUpdates -> EngineCommand.CheckForUpdates
        EngineManagementIntent.RestartEngine -> EngineCommand.Restart
        EngineManagementIntent.CancelEngineJob -> EngineCommand.Cancel
        EngineManagementIntent.DismissEngineJob -> EngineCommand.Dismiss
        is EngineManagementIntent.RequestEngineAction -> EngineCommand.Start(intent.action.toAction(intent.method))
        EngineManagementIntent.ConfirmEngineAction -> confirmAction?.let { EngineCommand.Start(it.toAction()) }
        EngineManagementIntent.SaveLaunch -> launchCommand()
        EngineManagementIntent.SubmitLoginCode -> codeCommand()
    }
}

/** Saves a changed draft without errors. */
private fun EngineConnectionsScreenState.launchCommand(): EngineCommand? = panel?.launch
    ?.takeIf { it.errors.isEmpty() && it.isDirty }
    ?.let { EngineCommand.Configure(it.draft.toSettings()) }

/** Passes a non-blank pasted code. */
private fun EngineConnectionsScreenState.codeCommand(): EngineCommand? =
    loginCode.trim().takeIf { it.isNotEmpty() }?.let { EngineCommand.AnswerLogin(LoginCode(it)) }
