package io.aequicor.heartbeat.core.featuretoggles

import kotlinx.coroutines.flow.Flow

/**
 * The single place to manage toggles inside the app — the backend of the toggle control panel. App-scoped.
 *
 * It sees every registered toggle and changes local overrides: they are stored on the device (app-wide, not per
 * profile), survive restarts and win over any other source. Every change is logged (`FT`, `I`). Feature code only
 * reads toggles through [FeatureToggles]: this interface is for the toggle panel and debug tooling (the convention
 * hook rejects it elsewhere). Changes are serialized. Storage failures propagate. All functions are main-safe.
 */
public interface FeatureToggleControl {
    /** Registered toggles, sorted by [FeatureToggle.owner], then [FeatureToggle.key]. */
    public val registered: List<FeatureToggle<*>>

    /** State of every [registered] toggle in the same order; emits on each change. */
    public fun observeStates(): Flow<List<ToggleState<*>>>

    /**
     * Overrides [toggle] with [value] locally, even if [value] equals the default: the override stays when the
     * default changes. Fails with [IllegalArgumentException] if [toggle] is not registered or [value] is not one of
     * [FeatureToggle.Choice.options].
     */
    public suspend fun <T : Any> setOverride(toggle: FeatureToggle<T>, value: T)

    /** Removes the local override of [toggle]: it returns to its default. Fails like [setOverride] if unregistered. */
    public suspend fun reset(toggle: FeatureToggle<*>)

    /** Removes every local override, including stale ones of toggles that are no longer declared. */
    public suspend fun resetAll()
}

/** A toggle with its current value and where the value comes from. */
public data class ToggleState<T : Any>(
    /** The toggle. */
    public val toggle: FeatureToggle<T>,
    /** Current value. */
    public val value: T,
    /** Source of [value]. */
    public val source: ToggleSource,
) {
    /** `true` when [value] is set locally rather than taken from the default. */
    public val isOverridden: Boolean get() = source == ToggleSource.LocalOverride
}

/**
 * Where the value of a toggle comes from, in priority order: the first present source wins.
 * A remote source (between the two) is a later decision.
 */
public enum class ToggleSource {
    /** Set on this device through [FeatureToggleControl]. */
    LocalOverride,

    /** [FeatureToggle.default]. */
    Default,
}
