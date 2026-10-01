package io.aequicor.heartbeat.feature.computeruse.api

/**
 * App-owned native presentation exclusion for computer capture and input. This does not change capture mode,
 * ownership or feature state: it temporarily removes app windows so pixels and input address the target beneath.
 */
public interface ComputerUseCapturePresentation {
    /**
     * Registers a native window controller for its entire window lifetime. Register and close on the main thread.
     * Closing prevents later suppression; an already-acquired restore lease remains until the action ends.
     * A presentation registered during an action is suppressed immediately, before this function returns.
     */
    public fun register(presentation: ComputerUsePresentation): AutoCloseable

    /**
     * Runs one capture or input operation while all registered presentations are excluded. Suppression and
     * restoration finish on the main thread even if the caller is cancelled; overlapping operations serialize
     * on a non-reentrant lock, so [action] must not call this function again.
     *
     * A failure of [action] stays primary and carries restore failures as suppressed exceptions. A restore
     * failure after a successful [action] fails the operation: the caller must treat input as possibly applied.
     */
    public suspend fun <T> withoutPresentation(action: suspend () -> T): T
}

/** Native window mechanics only, without a dependency on UI or the feature's business flow. */
public fun interface ComputerUsePresentation {
    /**
     * Immediately excludes this presentation and returns its main-thread restore lease. Restore recovers the
     * previous visibility without activating the app; a disposed presentation must never be resurrected.
     * A failure must leave the controller in its original visibility before throwing.
     */
    public fun suppress(): AutoCloseable
}
