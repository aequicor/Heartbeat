package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode

/** Management of the panel's engine: requests the machine executes and edits of local input. */
sealed interface EngineManagementIntent : EngineControlPanelIntent {
    /** A request the machine executes as an engine management command. */
    sealed interface EngineRequest : EngineManagementIntent

    /** An edit of local input (a launch draft, a sign-in code, a pending confirmation). */
    sealed interface EngineInput : EngineManagementIntent

    /** Switches the engine on or off in this profile. */
    data class SetEngineEnabled(val isEnabled: Boolean) : EngineRequest

    /** Saves MCP access independently of the launch editor; never submits an unsaved draft. */
    data class SetMcpEnabled(val isEnabled: Boolean) : EngineRequest

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

    /** Opens the engine's host application. */
    data object OpenHostApp : EngineRequest
}

/** Removals, a package sync, an update replacing the verified bundled copy and sign-out ask first. */
internal fun EngineControlPanelState.needsConfirmation(action: EngineActionUi): Boolean = when (action) {
    // A sync also removes the installed packages that are no longer listed.
    EngineActionUi.Uninstall, EngineActionUi.Logout, EngineActionUi.SyncPackages -> true

    EngineActionUi.Update -> panel?.installation?.support == InstallSupportUi.Bundled

    EngineActionUi.Install, EngineActionUi.Login, EngineActionUi.UpdatePackages -> false
}

/** Whether [intent] edits the launch draft, which changes the panel's validation. */
internal val EngineManagementIntent.EngineInput.isLaunchEdit: Boolean
    get() = this is EngineManagementIntent.EditLaunch || this == EngineManagementIntent.DiscardLaunch ||
        this == EngineManagementIntent.ResetLaunch

/** The local input after [intent]. */
internal fun EngineControlPanelState.edit(intent: EngineManagementIntent.EngineInput) = when (intent) {
    is EngineManagementIntent.EditLaunch -> copy(launchDraft = intent.draft)
    EngineManagementIntent.DiscardLaunch -> copy(launchDraft = null)
    EngineManagementIntent.ResetLaunch -> copy(launchDraft = LaunchDraftUi())
    is EngineManagementIntent.EditLoginCode -> copy(loginCode = intent.code)
    EngineManagementIntent.DismissEngineAction -> copy(confirmAction = null)
}

/** The local input once the machine accepted [intent]: what it sent is cleared. */
internal fun EngineControlPanelState.sent(intent: EngineManagementIntent.EngineRequest) = when (intent) {
    EngineManagementIntent.SaveLaunch -> copy(launchDraft = null)

    EngineManagementIntent.SubmitLoginCode -> copy(loginCode = "")

    EngineManagementIntent.ConfirmEngineAction -> copy(confirmAction = null)

    is EngineManagementIntent.SetEngineEnabled, is EngineManagementIntent.SetMcpEnabled,
    EngineManagementIntent.InspectEngine,
    EngineManagementIntent.CheckUpdates, EngineManagementIntent.RestartEngine,
    is EngineManagementIntent.RequestEngineAction, EngineManagementIntent.CancelEngineJob,
    EngineManagementIntent.DismissEngineJob, EngineManagementIntent.OpenHostApp,
    -> this
}

/** The management command of an engine request, or null when the panel's state does not allow it. */
internal fun EngineControlPanelState.commandFor(intent: EngineManagementIntent.EngineRequest): EngineCommand? {
    if (panel == null) return null
    return when (intent) {
        is EngineManagementIntent.SetEngineEnabled -> EngineCommand.SetEnabled(intent.isEnabled)

        is EngineManagementIntent.SetMcpEnabled -> mcpCommand(intent.isEnabled)

        EngineManagementIntent.InspectEngine -> EngineCommand.Inspect

        EngineManagementIntent.CheckUpdates -> EngineCommand.CheckForUpdates

        EngineManagementIntent.RestartEngine -> EngineCommand.Restart

        EngineManagementIntent.CancelEngineJob -> EngineCommand.Cancel

        EngineManagementIntent.DismissEngineJob -> EngineCommand.Dismiss

        is EngineManagementIntent.RequestEngineAction -> EngineCommand.Start(intent.action.toAction(intent.method))

        EngineManagementIntent.ConfirmEngineAction -> confirmedCommand()

        EngineManagementIntent.SaveLaunch -> launchCommand()

        EngineManagementIntent.SubmitLoginCode -> codeCommand()

        EngineManagementIntent.OpenHostApp ->
            EngineCommand.OpenHostApp.takeIf { EngineActionKindUi.OpenHostApp in panel.actions }
    }
}

/** A separate MCP switch never commits unrelated launch-editor input. */
private fun EngineControlPanelState.mcpCommand(isEnabled: Boolean): EngineCommand? = panel?.launch
    ?.takeIf { !it.isEditing && LaunchOptionUi.Mcp in it.options }
    ?.let { EngineCommand.Configure(it.draft.toSettings().copy(isMcpEnabled = isEnabled)) }

/** Saves a changed draft without errors. */
private fun EngineControlPanelState.launchCommand(): EngineCommand? = panel?.launch
    ?.takeIf { it.errors.isEmpty() && it.isDirty }
    ?.let { EngineCommand.Configure(it.draft.toSettings()) }

/** Passes a non-blank pasted code. */
private fun EngineControlPanelState.codeCommand(): EngineCommand? =
    loginCode.trim().takeIf { it.isNotEmpty() }?.let { EngineCommand.AnswerLogin(LoginCode(it)) }

/** Starts the previously confirmed action. */
private fun EngineControlPanelState.confirmedCommand(): EngineCommand? =
    confirmAction?.let { EngineCommand.Start(it.toAction()) }
