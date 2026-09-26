package io.aequicor.heartbeat.core.di

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Per-scope saved state — the scope-level analogue of `SavedStateHandle`.
 *
 * Objects inside a scope [consume] their state once when created and [register] a supplier that is
 * called when the platform saves state. The owner of the scope (see `core:di:ext`) persists
 * [snapshot] through the component's `StateKeeper`, so the state survives configuration changes
 * and process death. Main thread only.
 */
public interface ScopeSavedState {
    /** Returns and removes the value saved under [key], or `null` (fresh start, or unreadable data). */
    public fun <T : Any> consume(key: String, serializer: KSerializer<T>): T?

    /** Registers the [supplier] of the value to save under [key]. One supplier per key. */
    public fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?)

    /** Removes the supplier registered under [key]. */
    public fun unregister(key: String)

    /** Current state of the scope: registered values plus restored values nobody consumed yet. */
    public fun snapshot(): SavedBundle
}

/** Serialized [ScopeSavedState]. */
@Serializable
public data class SavedBundle(
    /** `key → JSON of the value`. */
    public val entries: Map<String, String>,
)
