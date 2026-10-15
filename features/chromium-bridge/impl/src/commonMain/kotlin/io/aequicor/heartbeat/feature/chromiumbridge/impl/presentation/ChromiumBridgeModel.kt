package io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeOutput
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ConnectedBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExtensionPreparation
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistration
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistrationFailure
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionIdentity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** Browsers as the screen names them. */
internal enum class BrowserUi { Chrome, Edge, Brave, Chromium, Vivaldi }

/** Registration of one browser. */
internal enum class RegistrationUi { NotRegistered, Current, OtherBuild }

/** The app socket. */
internal enum class ServerUi { Starting, Listening, Occupied, Failed }

/** Why a registration change failed. */
internal enum class FailureUi { UnsupportedPlatform, Translocated, NoLauncher, WriteFailed }

/** Whether the screen can show the bridge. */
internal enum class AvailabilityUi { Loading, Disabled, Unsupported, Enabled }

/** One browser row. */
@Immutable
internal data class BrowserRow(val browser: BrowserUi, val isDetected: Boolean, val registration: RegistrationUi)

/** One connected extension. */
@Immutable
internal data class ConnectionRow(
    val id: Long,
    val brand: String,
    val version: String,
    val extensionVersion: String,
    val isAgentControlAllowed: Boolean,
)

/** The last failed registration change. */
@Immutable
internal data class RegistrationError(val browser: BrowserUi, val reason: FailureUi)

/** Bridge settings as shown. */
@Immutable
internal data class ChromiumBridgeScreenState(
    val availability: AvailabilityUi = AvailabilityUi.Loading,
    val server: ServerUi = ServerUi.Starting,
    /** Browsers installed for this user plus any with a registration. */
    val browsers: ImmutableList<BrowserRow> = persistentListOf(),
    val connections: ImmutableList<ConnectionRow> = persistentListOf(),
    /** The browser whose registration is changing; its buttons are locked. */
    val busy: BrowserUi? = null,
    val isAgentControlEnabled: Boolean = false,
    val controlledTabs: Int = 0,
    val extensionId: String = ExtensionIdentity.ids.first(),
    /** The folder to load unpacked, or `null` when this build has none. */
    val extensionFolder: String? = null,
    val extensionPreparation: ExtensionPreparationUi = ExtensionPreparationUi(),
    val error: RegistrationError? = null,
) : MVIState

/** Settings commands. */
internal sealed interface ChromiumBridgeScreenIntent : MVIIntent {
    /** Makes [browser] start this build's host. */
    data class Register(val browser: BrowserUi) : ChromiumBridgeScreenIntent

    /** Removes the host registration from [browser]. */
    data class Unregister(val browser: BrowserUi) : ChromiumBridgeScreenIntent

    /** Re-reads browsers and registrations. */
    data object Rescan : ChromiumBridgeScreenIntent

    /** Takes the tabs agents drive back. */
    data object RevokeTabs : ChromiumBridgeScreenIntent

    /** Opens the extension folder in the file manager. */
    data object RevealExtension : ChromiumBridgeScreenIntent

    /** Hides the last registration error. */
    data object DismissError : ChromiumBridgeScreenIntent
}

/** No one-off screen actions. */
internal sealed interface ChromiumBridgeScreenAction : MVIAction

/** Settings store reflecting the profile's bridge machine; closing settings changes nothing of the bridge. */
internal class ChromiumBridgeModel(
    machine: Machine<ChromiumBridgeState, ChromiumBridgeIntent, ChromiumBridgeOutput>,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    /** The settings store, retained for the lifetime of this screen. */
    val store = factory.create<ChromiumBridgeScreenState, ChromiumBridgeScreenIntent, ChromiumBridgeScreenAction>(
        "ChromiumBridge",
        ChromiumBridgeScreenState().reflectState(machine.state.value),
        onError = { this },
    ) {
        reflect(
            machine,
            onOutput = { output ->
                when (output) {
                    is ChromiumBridgeOutput.RegistrationFailed -> updateState {
                        copy(error = RegistrationError(output.browser.toUi(), output.reason.toUi()))
                    }
                }
            },
        ) { reflectState(it) }
        reduce { intent ->
            when (intent) {
                is ChromiumBridgeScreenIntent.Register -> {
                    updateState { copy(error = null) }
                    sendTo(machine, ChromiumBridgeIntent.Public.Register(intent.browser.toBrowser()))
                }

                is ChromiumBridgeScreenIntent.Unregister -> {
                    updateState { copy(error = null) }
                    sendTo(machine, ChromiumBridgeIntent.Public.Unregister(intent.browser.toBrowser()))
                }

                ChromiumBridgeScreenIntent.Rescan -> sendTo(machine, ChromiumBridgeIntent.Public.Rescan)

                ChromiumBridgeScreenIntent.RevokeTabs -> sendTo(machine, ChromiumBridgeIntent.Public.RevokeTabs)

                ChromiumBridgeScreenIntent.RevealExtension ->
                    sendTo(machine, ChromiumBridgeIntent.Public.RevealExtension)

                ChromiumBridgeScreenIntent.DismissError -> updateState { copy(error = null) }
            }
        }
    }

    init {
        store.start(scope)
    }
}

/** The screen mirrors the machine; it keeps only the shown error of its own. */
internal fun ChromiumBridgeScreenState.reflectState(state: ChromiumBridgeState): ChromiumBridgeScreenState =
    when (state) {
        ChromiumBridgeState.Idle -> copy(availability = AvailabilityUi.Loading)

        is ChromiumBridgeState.Disabled -> copy(
            availability = if (state.isSupported) AvailabilityUi.Disabled else AvailabilityUi.Unsupported,
            connections = persistentListOf(),
            busy = null,
        )

        is ChromiumBridgeState.Enabled -> copy(
            availability = AvailabilityUi.Enabled,
            server = state.server.toUi(),
            browsers = state.hosts
                .filter { it.isDetected || it.registration != HostRegistration.NotRegistered }
                .map { it.toRow() }
                .toImmutableList(),
            connections = state.connections.map { it.toRow() }.toImmutableList(),
            busy = state.registering?.toUi(),
            isAgentControlEnabled = state.isAgentControlEnabled,
            controlledTabs = state.controlledTabs,
            extensionFolder = state.extensionFolder,
            extensionPreparation = state.extensionPreparation.toUi(),
        )
    }

private fun BrowserHost.toRow() = BrowserRow(browser.toUi(), isDetected, registration.toUi())

private fun ConnectedBrowser.toRow() = ConnectionRow(id, brand, version, extensionVersion, isAgentControlAllowed)

private fun ChromiumBrowser.toUi(): BrowserUi = when (this) {
    ChromiumBrowser.Chrome -> BrowserUi.Chrome
    ChromiumBrowser.Edge -> BrowserUi.Edge
    ChromiumBrowser.Brave -> BrowserUi.Brave
    ChromiumBrowser.Chromium -> BrowserUi.Chromium
    ChromiumBrowser.Vivaldi -> BrowserUi.Vivaldi
}

private fun BrowserUi.toBrowser(): ChromiumBrowser = when (this) {
    BrowserUi.Chrome -> ChromiumBrowser.Chrome
    BrowserUi.Edge -> ChromiumBrowser.Edge
    BrowserUi.Brave -> ChromiumBrowser.Brave
    BrowserUi.Chromium -> ChromiumBrowser.Chromium
    BrowserUi.Vivaldi -> ChromiumBrowser.Vivaldi
}

private fun HostRegistration.toUi(): RegistrationUi = when (this) {
    HostRegistration.NotRegistered -> RegistrationUi.NotRegistered
    HostRegistration.Current -> RegistrationUi.Current
    HostRegistration.OtherBuild -> RegistrationUi.OtherBuild
}

private fun BridgeServerStatus.toUi(): ServerUi = when (this) {
    BridgeServerStatus.Starting -> ServerUi.Starting
    BridgeServerStatus.Listening -> ServerUi.Listening
    BridgeServerStatus.Occupied -> ServerUi.Occupied
    BridgeServerStatus.Failed -> ServerUi.Failed
}

private fun HostRegistrationFailure.toUi(): FailureUi = when (this) {
    HostRegistrationFailure.UnsupportedPlatform -> FailureUi.UnsupportedPlatform
    HostRegistrationFailure.Translocated -> FailureUi.Translocated
    HostRegistrationFailure.NoLauncher -> FailureUi.NoLauncher
    HostRegistrationFailure.WriteFailed -> FailureUi.WriteFailed
}

/** The view renders preparation without exposing download metadata or IO to composition. */
@Immutable
internal data class ExtensionPreparationUi(
    val phase: ExtensionPreparationPhase = ExtensionPreparationPhase.Idle,
    val percent: Int? = null,
)

internal enum class ExtensionPreparationPhase { Idle, Downloading, Extracting, Failed }

private fun ExtensionPreparation.toUi(): ExtensionPreparationUi = when (this) {
    ExtensionPreparation.Idle -> ExtensionPreparationUi()

    is ExtensionPreparation.Downloading -> ExtensionPreparationUi(
        ExtensionPreparationPhase.Downloading,
        total?.takeIf { it > 0 }?.let { ((received.coerceIn(0, it).toDouble() / it) * PERCENT_SCALE).toInt() },
    )

    ExtensionPreparation.Extracting -> ExtensionPreparationUi(ExtensionPreparationPhase.Extracting)

    ExtensionPreparation.Failed -> ExtensionPreparationUi(ExtensionPreparationPhase.Failed)
}

private const val PERCENT_SCALE = 100
