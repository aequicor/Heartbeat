package io.aequicor.heartbeat.core.featuretoggles

import kotlinx.coroutines.flow.Flow

/**
 * Current values of feature toggles, for feature code (stores, machine effects, repositories). App-scoped.
 *
 * ```
 * @Inject class ChatEffectsImpl(private val toggles: FeatureToggles) : ChatEffects {
 *     val streaming: Flow<Boolean> = toggles.observe(ChatToggles.StreamingResponses)
 * }
 * ```
 * Composables never read toggles directly — only through the state of their store.
 *
 * The value is resolved by [ToggleSource]: a local override, otherwise [FeatureToggle.default]. Reading a toggle
 * that is not registered works, but logs a warning: it is invisible in the control panel. If the storage fails,
 * the toggle reads as its default (logged) — reads never throw because of the storage. All functions are main-safe.
 */
public interface FeatureToggles {
    /** Current value of [toggle] and its changes (distinct). */
    public fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T>

    /** Current value of [toggle]. */
    public suspend fun <T : Any> get(toggle: FeatureToggle<T>): T
}
