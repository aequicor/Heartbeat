package io.aequicor.heartbeat.core.featuretoggles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration.Companion.seconds

/**
 * [FeatureToggles]: a local override, otherwise the default. Resolved values are logged at `D`.
 * A storage failure never reaches feature code: the toggle reads as its default (`W`) and observation retries.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DataStoreFeatureToggles(private val registry: ToggleRegistry, private val overrides: ToggleOverrides) :
    FeatureToggles {

    private val log = Log.tag(FT_LOG_TAG)

    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        registry.verify(toggle)
        return overrides.observe(toggle)
            .map { toggle.stateOf(it) }
            .retryWhen { e, _ ->
                if (e is CancellationException) throw e
                log.w(e) { "${toggle.key}: overrides are unavailable, using the default" }
                emit(toggle.stateOf(null))
                delay(OBSERVE_RETRY_DELAY)
                true
            }
            .onEach { log.d { "${toggle.key} = ${it.value} (${it.source})" } }
            .map { it.value }
            .distinctUntilChanged()
    }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
        registry.verify(toggle)
        val state = try {
            overrides.state(toggle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "${toggle.key}: overrides are unavailable, using the default" }
            toggle.stateOf(null)
        }
        log.d { "get ${toggle.key} -> ${state.value} (${state.source})" }
        return state.value
    }

    private companion object {
        val OBSERVE_RETRY_DELAY = 1.seconds
    }
}
