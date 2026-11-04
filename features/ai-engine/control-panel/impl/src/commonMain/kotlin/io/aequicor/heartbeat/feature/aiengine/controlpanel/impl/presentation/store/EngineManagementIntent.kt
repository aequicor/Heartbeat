package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineOwnedJob
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
    data class CancelEngineJob(val handle: EngineOwnedJob) : EngineRequest

    /** Hides the outcome of the last job. */
    data class DismissEngineJob(val handle: EngineOwnedJob) : EngineRequest

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

/** Applicable removals, a package sync, an update replacing the verified bundled copy and sign-out ask first. */
internal fun EngineControlPanelState.needsConfirmation(action: EngineActionUi): Boolean = when (action) {
    EngineActionUi.Uninstall -> panel?.isUninstallAllowed == true

    // A sync also removes the installed packages that are no longer listed.
    EngineActionUi.Logout, EngineActionUi.SyncPackages -> true

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
    is EngineManagementIntent.RequestEngineAction, is EngineManagementIntent.CancelEngineJob,
    is EngineManagementIntent.DismissEngineJob, EngineManagementIntent.OpenHostApp,
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

        is EngineManagementIntent.CancelEngineJob -> EngineCommand.CancelOwned(intent.handle)

        is EngineManagementIntent.DismissEngineJob -> EngineCommand.DismissOwned(intent.handle)

        is EngineManagementIntent.RequestEngineAction -> requestedCommand(intent)

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

/** Checks the latest panel snapshot before opening a confirmation or dispatching an action. */
internal fun EngineControlPanelState.canRequest(
    action: EngineActionUi,
    method: LoginMethodUi = LoginMethodUi.Browser,
): Boolean {
    val current = panel ?: return false
    if (isSaving || current.job?.isRunning == true) return false
    val kind = when (action) {
        EngineActionUi.Install -> EngineActionKindUi.Install
        EngineActionUi.Update -> EngineActionKindUi.Update
        EngineActionUi.Uninstall -> EngineActionKindUi.Uninstall
        EngineActionUi.Login -> EngineActionKindUi.Login
        EngineActionUi.Logout -> EngineActionKindUi.Logout
        EngineActionUi.SyncPackages, EngineActionUi.UpdatePackages -> EngineActionKindUi.Packages
    }
    if (kind !in current.actions) return false
    if (action == EngineActionUi.Uninstall && !current.isUninstallAllowed) return false
    return action != EngineActionUi.Login || method in current.loginMethods
}

/** Starts a requested action only while its safety preconditions still hold. */
private fun EngineControlPanelState.requestedCommand(
    intent: EngineManagementIntent.RequestEngineAction,
): EngineCommand? = EngineCommand.Start(intent.action.toAction(intent.method))
    .takeIf { canRequest(intent.action, intent.method) }

/** Starts the previously confirmed action after rechecking it against the latest snapshot. */
private fun EngineControlPanelState.confirmedCommand(): EngineCommand? = confirmAction
    ?.takeIf { canRequest(it) }
    ?.let { EngineCommand.Start(it.toAction()) }
