package io.aequicor.heartbeat.core.di

/**
 * App-scoped work that belongs to no screen and no profile and starts once per process, right after the app graph
 * is created (e.g. a background check of the installed app). Only the desktop host starts them; Android and iOS
 * hosts do not call them. Called on the main thread: returns at once and launches longer work in the app scope.
 * A failing startup is logged by the host and does not stop the others.
 */
public fun interface AppStartup {
    /** Starts the work; called once. */
    public fun start()
}
