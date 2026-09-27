package io.aequicor.heartbeat.core.featuretoggles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [FeatureToggleControl] over [ToggleOverrides]. Every change is logged: `FT I key: old (source) -> new (source)`.
 * Changes are serialized, so the logged old value is the one actually replaced. Storage failures propagate.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class FeatureToggleControlImpl(private val registry: ToggleRegistry, private val overrides: ToggleOverrides) :
    FeatureToggleControl {

    private val log = Log.tag(FT_LOG_TAG)
    private val changeLock = Mutex()

    override val registered: List<FeatureToggle<*>> get() = registry.toggles

    override fun observeStates(): Flow<List<ToggleState<*>>> {
        val states: List<Flow<ToggleState<*>>> = registry.toggles.map { observeState(it) }
        return if (states.isEmpty()) flowOf(emptyList()) else combine(states) { it.toList() }
    }

    override suspend fun <T : Any> setOverride(toggle: FeatureToggle<T>, value: T) {
        requireRegistered(toggle)
        if (toggle is FeatureToggle.Choice) {
            require(toggle.options.any { it == value }) { "${toggle.key}: '$value' is not one of ${toggle.options}" }
        }
        changeLock.withLock {
            val old = overrides.state(toggle)
            overrides.set(toggle, value)
            log.i { "${toggle.key}: ${old.describe()} -> $value (${ToggleSource.LocalOverride})" }
        }
    }

    override suspend fun reset(toggle: FeatureToggle<*>) {
        requireRegistered(toggle)
        changeLock.withLock {
            val old = overrides.state(toggle)
            overrides.remove(toggle)
            if (old.isOverridden) {
                log.i { "${toggle.key}: ${old.describe()} -> ${toggle.default} (${ToggleSource.Default})" }
            } else {
                log.d { "${toggle.key}: reset, was not overridden" }
            }
        }
    }

    override suspend fun resetAll() {
        changeLock.withLock {
            val overridden = registry.toggles.map { overrides.state(it) }.filter { it.isOverridden }
            overrides.clear()
            overridden.forEach { old ->
                log.i { "${old.toggle.key}: ${old.describe()} -> ${old.toggle.default} (${ToggleSource.Default})" }
            }
            log.i { "reset all: ${overridden.size} overrides of registered toggles removed, stale keys cleared" }
        }
    }

    private fun requireRegistered(toggle: FeatureToggle<*>) {
        require(registry.isRegistered(toggle)) { "${toggle.key} is not registered: $toggle" }
    }

    private fun <T : Any> observeState(toggle: FeatureToggle<T>): Flow<ToggleState<T>> =
        overrides.observe(toggle).map { toggle.stateOf(it) }

    private fun ToggleState<*>.describe(): String = "$value ($source)"
}
