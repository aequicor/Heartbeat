package io.aequicor.heartbeat.core.featuretoggles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Toggles contributed to `Set<FeatureToggle<*>>`. Two different declarations with one key fail the creation
 * (a programming error: the value would be shared by unrelated toggles).
 */
@SingleIn(AppScope::class)
@Inject
internal class ToggleRegistry(declared: Set<FeatureToggle<*>>) {

    private val log = Log.tag(FT_LOG_TAG)
    private val byKey: Map<String, FeatureToggle<*>>
    private val warnedKeys = MutableStateFlow(emptySet<String>())

    /** Sorted by owner, then key. */
    val toggles: List<FeatureToggle<*>>

    init {
        val conflicts = declared.groupBy { it.key }.filterValues { it.size > 1 }
        check(conflicts.isEmpty()) {
            "toggle keys declared more than once with different definitions: ${conflicts.values.flatten()}"
        }
        byKey = declared.associateBy { it.key }
        toggles = declared.sortedWith(compareBy({ it.owner }, { it.key }))
        log.i { "registered ${toggles.size} toggles: ${toggles.joinToString { it.key }}" }
    }

    /** `true` when exactly this declaration is registered. */
    fun isRegistered(toggle: FeatureToggle<*>): Boolean = byKey[toggle.key] == toggle

    /** Warns (once per key) about a toggle read without registration or with a declaration unlike the registered. */
    fun verify(toggle: FeatureToggle<*>) {
        val registered = byKey[toggle.key]
        if (registered == toggle || toggle.key in warnedKeys.getAndUpdate { it + toggle.key }) return
        if (registered == null) {
            log.w { "${toggle.key} is read but not registered: invisible in the control panel" }
        } else {
            log.w { "${toggle.key} is read as $toggle, registered as $registered" }
        }
    }
}
