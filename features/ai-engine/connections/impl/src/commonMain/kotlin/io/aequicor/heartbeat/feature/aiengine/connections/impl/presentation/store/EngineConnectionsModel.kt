package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.EngineConnectionsScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

/** User events of the settings space. Navigation is handled by the component, not the store. */
sealed interface EngineConnectionsScreenIntent : MVIIntent {
    /** A request executed by the machine as a [ConnectionOperation]. */
    sealed interface Change : EngineConnectionsScreenIntent

    /** Focuses an engine. */
    data class SelectEngine(val id: String) : EngineConnectionsScreenIntent

    /** Focuses a connection of the focused engine. */
    data class SelectConnection(val id: String) : EngineConnectionsScreenIntent

    /** Probes the focused engine's installation. */
    data object ProbeEngine : Change

    /** Enables or disables a connection. */
    data class SetConnectionEnabled(val id: String, val isEnabled: Boolean) : Change

    /** Asks to confirm disconnecting a connection. */
    data class RequestDisconnect(val id: String) : EngineConnectionsScreenIntent

    /** Confirms the pending disconnect. */
    data object ConfirmDisconnect : Change

    /** Keeps the connection. */
    data object DismissDisconnect : EngineConnectionsScreenIntent

    /** Explicit model discovery for the focused connection. */
    data object RefreshModels : Change

    /** Filters models. */
    data class SearchModels(val query: String) : EngineConnectionsScreenIntent

    /** Offers or hides a model of the focused connection. */
    data class SetModelEnabled(val id: String, val isEnabled: Boolean) : Change

    /** Enables all cached models, or none. */
    data class SetAllModels(val isEnabled: Boolean) : Change

    /** Makes a model of the focused connection the profile default. */
    data class SetDefaultModel(val id: String) : Change

    /** Restarts a failed observation. */
    data object RetryLoad : EngineConnectionsScreenIntent

    /** Repeats a failed change. */
    data object RetryFailed : EngineConnectionsScreenIntent

    /** Hides the change failure. */
    data object DismissError : EngineConnectionsScreenIntent

    /** Engine management of the focused engine. */
    sealed interface Engine : EngineConnectionsScreenIntent

    /** A request the machine executes as an engine management command. */
    sealed interface EngineRequest : Engine

    /** An edit of local input (a launch draft, a sign-in code, a pending confirmation). */
    sealed interface EngineInput : Engine

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
    data class EditLoginCode(val code: String) : EngineInput

    /** Passes the pasted code to the signing-in CLI. */
    data object SubmitLoginCode : EngineRequest
}

/** The settings space has no one-off screen actions. */
sealed interface EngineConnectionsScreenAction : MVIAction

/** Settings store: reflects the machine snapshot and keeps only the focus, search and confirmation locally. */
@SingleIn(EngineConnectionsScope::class)
@Inject
class EngineConnectionsModel(
    private val machine: Machine<EngineConnectionsState, EngineConnectionsIntent, EngineConnectionsOutput>,
    @ForScope(EngineConnectionsScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("EngineConnectionsModel")

    val store = factory.create<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >(
        "EngineConnections",
        EngineConnectionsScreenState().reflect(machine.state.value),
        onError = { copy(isLoading = false, isSaving = false, failure = FailureUi.Unknown) },
    ) {
        init { sendTo(machine, EngineConnectionsIntent.Public.Start) }
        reflect(machine) { reflect(it) }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope.coroutineScope)
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.handle(
        intent: EngineConnectionsScreenIntent,
    ) {
        when (intent) {
            is EngineConnectionsScreenIntent.SelectEngine -> updateState {
                copy(
                    selectedEngine = intent.id,
                    selectedConnection = null,
                    confirmDisconnect = null,
                    launchDraft = null,
                    loginCode = "",
                    confirmAction = null,
                ).reflect(machine.state.value)
            }

            is EngineConnectionsScreenIntent.SelectConnection -> updateState {
                copy(selectedConnection = intent.id, confirmDisconnect = null).reflect(machine.state.value)
            }

            is EngineConnectionsScreenIntent.RequestDisconnect -> updateState { copy(confirmDisconnect = intent.id) }

            EngineConnectionsScreenIntent.DismissDisconnect -> updateState { copy(confirmDisconnect = null) }

            is EngineConnectionsScreenIntent.SearchModels -> updateState { copy(modelQuery = intent.query) }

            EngineConnectionsScreenIntent.RetryLoad -> sendTo(machine, EngineConnectionsIntent.Public.RetryLoad)

            EngineConnectionsScreenIntent.RetryFailed -> sendTo(machine, EngineConnectionsIntent.Public.RetryFailed)

            EngineConnectionsScreenIntent.DismissError -> sendTo(machine, EngineConnectionsIntent.Public.DismissError)

            is EngineConnectionsScreenIntent.Change -> apply(intent)

            is EngineConnectionsScreenIntent.Engine -> manage(intent)
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.manage(
        intent: EngineConnectionsScreenIntent.Engine,
    ) {
        when (intent) {
            is EngineConnectionsScreenIntent.EngineInput -> updateState { edit(intent).reflect(machine.state.value) }
            is EngineConnectionsScreenIntent.EngineRequest -> request(intent)
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.request(
        intent: EngineConnectionsScreenIntent.EngineRequest,
    ) {
        var screen = EngineConnectionsScreenState()
        withState { screen = this }
        if (intent is EngineConnectionsScreenIntent.RequestEngineAction && screen.needsConfirmation(intent.action)) {
            updateState { copy(confirmAction = intent.action) }
            return
        }
        val engine = screen.selectedEngine
        val command = screen.commandFor(intent)
        if (engine == null || command == null) {
            log.w { "ignore ${intent::class.simpleName.orEmpty()} without an applicable engine command" }
            return
        }
        val operation = ConnectionOperation.ManageEngine(EngineId(engine), command)
        val result = sendTo(machine, EngineConnectionsIntent.Public.Apply(operation))
        // Local input is cleared only once the machine took it; a rejected one stays to be sent again.
        if (result == SendResult.Accepted) updateState { sent(intent) }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.apply(
        intent: EngineConnectionsScreenIntent.Change,
    ) {
        var screen = EngineConnectionsScreenState()
        withState { screen = this }
        val operation = screen.operationFor(intent)
        if (operation == null) {
            log.w { "ignore ${intent::class.simpleName.orEmpty()} without a focused entry" }
            return
        }
        val result = sendTo(machine, EngineConnectionsIntent.Public.Apply(operation))
        // The confirmation closes only once the machine took the disconnect; a rejected one stays to be confirmed.
        if (intent == EngineConnectionsScreenIntent.ConfirmDisconnect && result == SendResult.Accepted) {
            updateState { copy(confirmDisconnect = null) }
        }
    }
}

/** Removals, an update replacing the verified bundled copy and sign-out ask first. */
internal fun EngineConnectionsScreenState.needsConfirmation(action: EngineActionUi): Boolean = when (action) {
    EngineActionUi.Uninstall, EngineActionUi.Logout -> true
    EngineActionUi.Update -> panel?.installation?.support == InstallSupportUi.Bundled
    EngineActionUi.Install, EngineActionUi.Login -> false
}

/** The local input after [intent]. */
internal fun EngineConnectionsScreenState.edit(intent: EngineConnectionsScreenIntent.EngineInput) = when (intent) {
    is EngineConnectionsScreenIntent.EditLaunch -> copy(launchDraft = intent.draft)
    EngineConnectionsScreenIntent.DiscardLaunch -> copy(launchDraft = null)
    EngineConnectionsScreenIntent.ResetLaunch -> copy(launchDraft = LaunchDraftUi())
    is EngineConnectionsScreenIntent.EditLoginCode -> copy(loginCode = intent.code)
    EngineConnectionsScreenIntent.DismissEngineAction -> copy(confirmAction = null)
}

/** The local input once the machine accepted [intent]: what it sent is cleared. */
private fun EngineConnectionsScreenState.sent(intent: EngineConnectionsScreenIntent.EngineRequest) = when (intent) {
    EngineConnectionsScreenIntent.SaveLaunch -> copy(launchDraft = null)

    EngineConnectionsScreenIntent.SubmitLoginCode -> copy(loginCode = "")

    EngineConnectionsScreenIntent.ConfirmEngineAction -> copy(confirmAction = null)

    is EngineConnectionsScreenIntent.SetEngineEnabled, EngineConnectionsScreenIntent.InspectEngine,
    EngineConnectionsScreenIntent.CheckUpdates, EngineConnectionsScreenIntent.RestartEngine,
    is EngineConnectionsScreenIntent.RequestEngineAction, EngineConnectionsScreenIntent.CancelEngineJob,
    EngineConnectionsScreenIntent.DismissEngineJob,
    -> this
}

/** The management command of an engine request, or null when the panel's state does not allow it. */
internal fun EngineConnectionsScreenState.commandFor(
    intent: EngineConnectionsScreenIntent.EngineRequest,
): EngineCommand? {
    if (panel == null) return null
    return when (intent) {
        is EngineConnectionsScreenIntent.SetEngineEnabled -> EngineCommand.SetEnabled(intent.isEnabled)

        EngineConnectionsScreenIntent.InspectEngine -> EngineCommand.Inspect

        EngineConnectionsScreenIntent.CheckUpdates -> EngineCommand.CheckForUpdates

        EngineConnectionsScreenIntent.RestartEngine -> EngineCommand.Restart

        EngineConnectionsScreenIntent.CancelEngineJob -> EngineCommand.Cancel

        EngineConnectionsScreenIntent.DismissEngineJob -> EngineCommand.Dismiss

        is EngineConnectionsScreenIntent.RequestEngineAction -> EngineCommand.Start(
            intent.action.toAction(intent.method),
        )

        EngineConnectionsScreenIntent.ConfirmEngineAction -> confirmAction?.let { EngineCommand.Start(it.toAction()) }

        EngineConnectionsScreenIntent.SaveLaunch -> launchCommand()

        EngineConnectionsScreenIntent.SubmitLoginCode -> codeCommand()
    }
}

/** Saves a changed draft without errors. */
private fun EngineConnectionsScreenState.launchCommand(): EngineCommand? = panel?.launch
    ?.takeIf { it.errors.isEmpty() && it.isDirty }
    ?.let { EngineCommand.Configure(it.draft.toSettings()) }

/** Passes a non-blank pasted code. */
private fun EngineConnectionsScreenState.codeCommand(): EngineCommand? =
    loginCode.trim().takeIf { it.isNotEmpty() }?.let { EngineCommand.AnswerLogin(LoginCode(it)) }

/** Translates a change request into a machine operation against the focused engine and connection. */
internal fun EngineConnectionsScreenState.operationFor(
    intent: EngineConnectionsScreenIntent.Change,
): ConnectionOperation? {
    val binding = selectedConnection?.let(::EngineBindingId)
    return when (intent) {
        EngineConnectionsScreenIntent.ProbeEngine -> selectedEngine?.let {
            ConnectionOperation.ProbeEngine(
                EngineId(it),
            )
        }

        is EngineConnectionsScreenIntent.SetConnectionEnabled ->
            ConnectionOperation.SetConnectionEnabled(EngineBindingId(intent.id), intent.isEnabled)

        EngineConnectionsScreenIntent.ConfirmDisconnect ->
            confirmDisconnect?.let { ConnectionOperation.Disconnect(EngineBindingId(it)) }

        EngineConnectionsScreenIntent.RefreshModels -> {
            val engine = selectedEngine?.let(::EngineId)
            if (engine != null && binding != null) ConnectionOperation.RefreshModels(engine, binding) else null
        }

        is EngineConnectionsScreenIntent.SetModelEnabled ->
            target(intent.id)?.let { ConnectionOperation.SetModelEnabled(it, intent.isEnabled) }

        is EngineConnectionsScreenIntent.SetAllModels -> allModels(intent.isEnabled)

        is EngineConnectionsScreenIntent.SetDefaultModel -> target(intent.id)?.let(ConnectionOperation::SetDefaultModel)
    }
}
