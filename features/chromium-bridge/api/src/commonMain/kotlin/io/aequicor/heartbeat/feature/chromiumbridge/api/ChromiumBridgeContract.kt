package io.aequicor.heartbeat.feature.chromiumbridge.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState

/** Profile state of the Chromium bridge. */
public sealed interface ChromiumBridgeState : MachineState {
    /** Availability not known yet. */
    public data object Idle : ChromiumBridgeState

    /** The bridge is off ([isSupported]: whether this platform could run it); no socket exists. */
    public data class Disabled(public val isSupported: Boolean) : ChromiumBridgeState

    /** The socket is served while this state lasts; leaving it closes and removes the socket. */
    public data class Enabled(
        public val server: BridgeServerStatus = BridgeServerStatus.Starting,
        public val hosts: List<BrowserHost> = emptyList(),
        public val connections: List<ConnectedBrowser> = emptyList(),
        /** Tabs agents currently drive in external browsers, as reported by the extensions. */
        public val controlledTabs: Int = 0,
        /** The browser whose registration is being changed; other changes wait for it. */
        public val registering: ChromiumBrowser? = null,
        /** `chromium_bridge.agent_control`: agents may drive external browsers. */
        public val isAgentControlEnabled: Boolean = false,
        /** Verified extension folder to load unpacked, or the development checkout. */
        public val extensionFolder: String? = null,
        /** First-use preparation, started only by an explicit reveal request. */
        public val extensionPreparation: ExtensionPreparation = ExtensionPreparation.Idle,
    ) : ChromiumBridgeState
}

/** State of the app socket. */
public enum class BridgeServerStatus {
    /** Binding the socket. */
    Starting,

    /** Hosts can connect. */
    Listening,

    /** Another Heartbeat process on this machine already serves the socket. */
    Occupied,

    /** The socket could not be bound. */
    Failed,
}

/** An extension connected through a host and past the handshake. */
public data class ConnectedBrowser(
    /** Connection id for the current app run. */
    public val id: Long,
    public val brand: String,
    public val version: String,
    public val extensionVersion: String,
    /** The user allowed agent control in this browser's extension. */
    public val isAgentControlAllowed: Boolean,
)

/** Why a host registration change failed. */
public enum class HostRegistrationFailure {
    /** Native messaging hosts are registered only on macOS and Windows. */
    UnsupportedPlatform,

    /** macOS runs the app from a temporary translocated copy; it must be moved to Applications first. */
    Translocated,

    /** Neither a packaged launcher nor a Java runtime can start the host. */
    NoLauncher,

    /** Writing the manifest, launcher or registry key failed. */
    WriteFailed,
}

/** Commands of the settings screen and results of the bridge's own work. */
public sealed interface ChromiumBridgeIntent : MachineIntent {
    /** What the settings screen and other features may send. */
    public sealed interface Public : ChromiumBridgeIntent {
        /** Makes [browser] start this build's host. */
        public data class Register(public val browser: ChromiumBrowser) : Public

        /** Removes the Heartbeat host registration from [browser]. */
        public data class Unregister(public val browser: ChromiumBrowser) : Public

        /** Re-reads browser installations and registrations. */
        public data object Rescan : Public

        /** Takes every tab agents drive in external browsers back from them. */
        public data object RevokeTabs : Public

        /** Prepares the pinned extension if needed, then shows its folder in the system file manager. */
        public data object RevealExtension : Public
    }

    /** Results the feature sends to itself. */
    public sealed interface Internal : ChromiumBridgeIntent {
        /** Toggles or platform support changed. */
        public data class AvailabilityChanged(
            public val isEnabled: Boolean,
            public val isSupported: Boolean,
            public val isAgentControlEnabled: Boolean,
        ) : Internal

        /** The socket changed state. */
        public data class ServerChanged(public val status: BridgeServerStatus) : Internal

        /** Fresh browser installations and registrations. */
        public data class HostsScanned(public val hosts: List<BrowserHost>) : Internal

        /** A registration change finished with [host]. */
        public data class RegistrationFinished(public val host: BrowserHost) : Internal

        /** A registration change of [browser] failed. */
        public data class RegistrationFailed(
            public val browser: ChromiumBrowser,
            public val reason: HostRegistrationFailure,
        ) : Internal

        /** The handshaken extensions changed. */
        public data class ConnectionsChanged(public val connections: List<ConnectedBrowser>) : Internal

        /** The number of tabs agents drive changed. */
        public data class TabsChanged(public val controlledTabs: Int) : Internal

        /** The extension folder was looked up; `null` when this build has none. */
        public data class ExtensionLocated(public val folder: String?) : Internal

        /** Download or extraction progress of the explicitly requested extension. */
        public data class ExtensionProgress(public val preparation: ExtensionPreparation) : Internal

        /** Preparation succeeded; the folder can now be revealed. */
        public data class ExtensionPrepared(public val folder: String) : Internal

        /** Preparation failed; another reveal request retries it. */
        public data object ExtensionFailed : Internal

        /** An effect without a dedicated failure intent failed; the runtime logged it. */
        public data object EffectFailed : Internal
    }
}

/** IO the bridge performs. */
public sealed interface ChromiumBridgeEffect : MachineEffect {
    /** Serves the socket and reports connections for as long as the bridge stays enabled. */
    public data object Serve : ChromiumBridgeEffect

    /** Reads browser installations and registrations. */
    public data object ScanHosts : ChromiumBridgeEffect

    /** Registers the host for [browser]. */
    public data class Register(public val browser: ChromiumBrowser) : ChromiumBridgeEffect

    /** Removes the host registration from [browser]. */
    public data class Unregister(public val browser: ChromiumBrowser) : ChromiumBridgeEffect

    /** Asks every connected extension to release the tabs agents drive. */
    public data object RevokeTabs : ChromiumBridgeEffect

    /** Looks up the extension folder of this build. */
    public data object LocateExtension : ChromiumBridgeEffect

    /** Downloads and safely extracts this build's pinned extension, reporting progress. */
    public data object PrepareExtension : ChromiumBridgeEffect

    /** Opens [folder] in the system file manager. */
    public data class RevealExtension(public val folder: String) : ChromiumBridgeEffect
}

/** One-off events for the settings screen. */
public sealed interface ChromiumBridgeOutput : MachineOutput {
    /** Registering or unregistering [browser] failed. */
    public data class RegistrationFailed(
        public val browser: ChromiumBrowser,
        public val reason: HostRegistrationFailure,
    ) : ChromiumBridgeOutput
}

/** Addresses the profile's Chromium bridge machine. */
public object ChromiumBridgeMachineKey :
    MachineKey<
        ChromiumBridgeState,
        ChromiumBridgeIntent,
        ChromiumBridgeIntent.Public,
        ChromiumBridgeEffect,
        ChromiumBridgeOutput,
    > {
    override val name: String = "chromium_bridge"
}
