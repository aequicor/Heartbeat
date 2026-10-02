package io.aequicor.heartbeat.feature.computeruse.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PermissionGrants
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias SettingsPipeline =
    PipelineContext<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>

/**
 * Host blockers presented beside the tool switch: a permission the user grants in the system settings gets its own
 * row with a button, everything else is localized help.
 */
internal enum class BlockerUi(val permission: ComputerUsePermission?) {
    UnsupportedPlatform(null),
    ScreenRecordingPermission(ComputerUsePermission.ScreenRecording),
    AccessibilityPermission(ComputerUsePermission.Accessibility),
    ElevationRequired(null),
    SessionLocked(null),
    Headless(null),
    ;

    /** `true` when the user can grant it in the operating system settings. */
    val isGrantable: Boolean get() = permission != null
}

/** Why the settings screen cannot show or keep the saved switch value. */
internal enum class SettingsError {
    /** The saved preference could not be read; the switch stays locked until its value is known. */
    LoadFailed,

    /** The last switch change could not be written; the switch keeps its previous value. */
    SaveFailed,
}

/** The persisted tool switch and relevant host availability; capture choices belong to the agent. */
@Immutable
internal data class ComputerUseScreenState(
    val isEnabled: Boolean = false,
    val isLoaded: Boolean = false,
    val blockers: ImmutableList<BlockerUi> = persistentListOf(),
    val error: SettingsError? = null,
) : MVIState

/** Controls offered by computer use settings. */
internal sealed interface ComputerUseScreenIntent : MVIIntent {
    /** Enables the tool, or revokes the active capture and disables further agent use. */
    data class SetEnabled(val isEnabled: Boolean) : ComputerUseScreenIntent

    /** Opens the system settings page of a missing permission together with the host's drag guide. */
    data class GrantPermission(val blocker: BlockerUi) : ComputerUseScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface ComputerUseScreenAction : MVIAction

/**
 * Settings store. It observes the profile preference and the machine's permission blockers, without holding
 * captured frames or choosing a capture target. Disabling revokes input and capture before saving the switch,
 * and both outlive the settings screen. A failed save keeps the previous value; a failed read keeps it locked.
 * A permission grant is handed to the profile's [PermissionGrants], so it continues after settings close.
 */
internal class ComputerUseModel(
    private val machine: Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>,
    private val preferences: ComputerUsePreferences,
    private val grants: PermissionGrants,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("ComputerUseModel")

    /** The settings store, retained for the lifetime of this screen. */
    val store = factory.create<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>(
        "ComputerUse",
        ComputerUseScreenState().reflectState(machine.state.value),
        // Saving handles its own failures, so what reaches here is the preference stream: the value stays unknown.
        onError = { copy(error = SettingsError.LoadFailed) },
    ) {
        reflect(machine) { reflectState(it) }
        whileSubscribed {
            preferences.observe().collect { settings ->
                updateState { copy(isEnabled = settings.isEnabled, isLoaded = true) }
            }
        }
        reduce { intent ->
            when (intent) {
                is ComputerUseScreenIntent.SetEnabled -> setEnabled(intent.isEnabled)
                is ComputerUseScreenIntent.GrantPermission -> grantPermission(intent.blocker)
            }
        }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SettingsPipeline.setEnabled(isEnabled: Boolean) {
        try {
            if (isEnabled) {
                preferences.setEnabled(true)
            } else {
                // Closing settings right after switching off must neither cancel the revoke nor the saved opt out.
                withContext(NonCancellable) {
                    sendTo(machine, ComputerUseIntent.Public.Revoke)
                    preferences.setEnabled(false)
                }
            }
            updateState {
                copy(
                    isEnabled = isEnabled,
                    isLoaded = true,
                    // A failed read stays visible: the preference stream it stopped is not observed again here.
                    error = error.takeUnless { it == SettingsError.SaveFailed },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "computer use switch was not saved enabled=$isEnabled" }
            // A failed read stays visible: its observer is gone, so a save cannot vouch for the shown value.
            updateState { copy(error = error.takeIf { it == SettingsError.LoadFailed } ?: SettingsError.SaveFailed) }
        }
    }

    /** Only a grantable blocker reaches the profile's grant flow; the others have no settings page. */
    private fun grantPermission(blocker: BlockerUi) {
        val permission = blocker.permission
        if (permission == null) {
            log.w { "no system settings page for blocker=$blocker" }
        } else {
            log.i { "permission grant requested from settings permission=$permission" }
            grants.request(permission)
        }
    }
}

/** Permission availability is reflected without duplicating capture targets or the active session. */
private fun ComputerUseScreenState.reflectState(state: ComputerUseState): ComputerUseScreenState = copy(
    blockers = when (state) {
        is ComputerUseState.Unavailable -> state.blockers.toUi()

        // Capture may be granted while input is not (macOS Accessibility): the agent's input then keeps failing.
        is ComputerUseState.Ready -> state.capabilities.blockers.toUi()

        is ComputerUseState.Capturing -> state.capabilities.blockers.toUi()

        ComputerUseState.Idle, ComputerUseState.Checking, is ComputerUseState.Failed -> persistentListOf()
    },
)

private fun List<ComputerUseBlocker>.toUi(): ImmutableList<BlockerUi> = map { it.toUi() }.toImmutableList()

private fun ComputerUseBlocker.toUi(): BlockerUi = when (this) {
    ComputerUseBlocker.UnsupportedPlatform -> BlockerUi.UnsupportedPlatform
    ComputerUseBlocker.ScreenRecordingPermission -> BlockerUi.ScreenRecordingPermission
    ComputerUseBlocker.AccessibilityPermission -> BlockerUi.AccessibilityPermission
    ComputerUseBlocker.ElevationRequired -> BlockerUi.ElevationRequired
    ComputerUseBlocker.SessionLocked -> BlockerUi.SessionLocked
    ComputerUseBlocker.Headless -> BlockerUi.Headless
}
