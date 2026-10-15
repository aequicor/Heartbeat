package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ConnectedBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExtensionPreparation
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistrationFailure
import kotlinx.coroutines.flow.Flow

/** Registration of the native-messaging host in the browsers' own locations. */
internal interface NativeHostRegistry {
    /** Current state of every supported browser; repairs this build's launcher when it went stale. */
    suspend fun scan(): List<BrowserHost>

    /**
     * Makes [browser] start this build's host.
     * @throws HostRegistrationException when this build cannot be registered (unsupported OS, translocated app).
     */
    suspend fun register(browser: ChromiumBrowser): BrowserHost

    /** Removes the Heartbeat host registration from [browser], whichever build wrote it. */
    suspend fun unregister(browser: ChromiumBrowser): BrowserHost
}

/** A registration refused for [problem]. */
internal class HostRegistrationException(val problem: HostRegistrationFailure, cause: Throwable? = null) :
    Exception("Host registration failed: $problem", cause)

/** The app end of the bridge: the socket hosts connect to and the extensions behind them. */
internal interface BridgeServer {
    /**
     * Serves until cancelled, reporting every status change. Returns only when the socket cannot be served
     * ([BridgeServerStatus.Occupied] or [BridgeServerStatus.Failed] reported before).
     */
    suspend fun serve(onStatus: suspend (BridgeServerStatus) -> Unit)

    /** Extensions past the handshake. */
    fun clients(): Flow<List<ConnectedBrowser>>

    /** Tabs agents drive across all connected browsers, as the extensions report them. */
    fun controlledTabs(): Flow<Int>

    /** Asks every extension allowing agent control to release the tabs agents drive; returns how many answered. */
    suspend fun revokeTabs(): Int
}

/** The extension prepared on demand or, in development, the repository's. */
internal interface ExtensionFolder {
    /** Local lookup only: never starts a download when the bridge is enabled. */
    suspend fun locate(): String?

    /** Prepares the pinned component after an explicit user action; cancellation leaves no partial folder. */
    suspend fun prepare(progress: suspend (ExtensionPreparation) -> Unit): String

    /** Opens [folder] in the system file manager. */
    suspend fun reveal(folder: String)
}
