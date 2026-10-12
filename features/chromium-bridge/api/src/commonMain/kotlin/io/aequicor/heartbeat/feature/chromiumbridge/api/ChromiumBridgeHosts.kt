package io.aequicor.heartbeat.feature.chromiumbridge.api

/** Chromium browsers the native-messaging host can be registered for. */
public enum class ChromiumBrowser { Chrome, Edge, Brave, Chromium, Vivaldi }

/** Whether a browser starts this Heartbeat build's host. */
public enum class HostRegistration {
    /** The browser has no host registered under the Heartbeat name. */
    NotRegistered,

    /** The browser starts this build's host for the Heartbeat extension. */
    Current,

    /** The registration points elsewhere: another Heartbeat build (development or installed) or a foreign file. */
    OtherBuild,
}

/** One browser as seen by the host registry. */
public data class BrowserHost(
    val browser: ChromiumBrowser,
    /** The browser's user data directory exists on this machine. */
    val isDetected: Boolean,
    val registration: HostRegistration,
)
