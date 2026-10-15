package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

/**
 * Retains cleanup for every used profile pool until it finishes, including a profile already signed out.
 * Desktop shutdown awaits the same cleanup as profile closure before cancelling the application scope.
 * Registration never creates a pool, and shutdown refuses registration of any new one.
 */
interface RuntimePoolShutdown {
    /** Registers cleanup without creating resources; the returned callback also closes it on profile shutdown. */
    fun register(close: suspend () -> Unit): suspend () -> Unit
}
