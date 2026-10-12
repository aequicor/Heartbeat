package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser

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

/** Why the host cannot be registered; shown to the user. */
internal enum class RegistrationProblem {
    /** Native messaging hosts are registered only on macOS and Windows. */
    UnsupportedPlatform,

    /** macOS runs the app from a temporary translocated copy; it must be moved to Applications first. */
    Translocated,

    /** Neither a packaged launcher nor a Java runtime is available to start the host. */
    NoLauncher,

    /** Writing the manifest, launcher or registry key failed. */
    WriteFailed,
}

/** A registration refused for [problem]. */
internal class HostRegistrationException(val problem: RegistrationProblem, cause: Throwable? = null) :
    Exception("Host registration failed: $problem", cause)
