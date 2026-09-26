package io.aequicor.heartbeat.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Coroutine dispatchers used by production code. Always injected — never reference `Dispatchers.*`
 * directly, so tests can substitute a test dispatcher.
 */
public interface DispatcherProvider {
    /** UI thread (Looper / Swing EDT / iOS main queue — the platform modules are wired by `core:common`). */
    public val main: CoroutineDispatcher

    /** CPU-bound work. */
    public val default: CoroutineDispatcher

    /** Blocking IO: files, database, network engines without native suspension. */
    public val io: CoroutineDispatcher
}
