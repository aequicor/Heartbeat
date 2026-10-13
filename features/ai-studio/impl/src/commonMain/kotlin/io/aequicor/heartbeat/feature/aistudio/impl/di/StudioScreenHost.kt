package io.aequicor.heartbeat.feature.aistudio.impl.di

/** Who shows a studio screen graph: the app window or a remote view (the browser side panel). */
enum class StudioScreenHost {
    /** The studio route of the app; its machine is the one other features address. */
    Window,

    /** A remote view; its machine is private to the view. */
    Remote,
}
