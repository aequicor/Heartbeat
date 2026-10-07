package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = Log.tag("Autocomplete/EngineAssists")

/**
 * Native engine assists of the composer, resolved through the facade without starting a session. Listings are
 * cached per engine route and workspace for a short time: typing must not hammer a native runtime, and a
 * failure degrades to the Heartbeat-only sections instead of blocking the popup.
 */
@Inject
internal class EngineAssistsSource(private val facade: EngineFacade, private val clock: Clock) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<Route, Entry>()

    /** Native assists visible on [target]; a null [workspace] is a chat without a project. */
    internal suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> {
        val route = Route(target.engine.value, target.binding.value, workspace?.value)
        val now = clock.now()
        mutex.withLock {
            cache[route]?.takeIf { now - it.fetchedAt < TTL }?.let { return it.assists }
        }
        val assists = read(target, workspace)
        mutex.withLock {
            cache[route] = Entry(now, assists)
        }
        return assists
    }

    private suspend fun read(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> =
        when (val resolved = facade.engines.features(target.engine).resolve(ListsComposerAssists)) {
            is FeatureAccess.Available -> try {
                resolved.feature.assists(target, workspace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "native composer assists unavailable for engine=${target.engine.value}" }
                emptyList()
            }

            is FeatureAccess.Unavailable -> {
                log.w { "native composer assists blocked for engine=${target.engine.value}" }
                emptyList()
            }

            FeatureAccess.Unsupported -> emptyList()
        }

    private data class Route(val engine: String, val binding: String, val workspace: String?)

    private data class Entry(val fetchedAt: kotlin.time.Instant, val assists: List<EngineAssist>)

    private companion object {
        val TTL: Duration = 60.seconds
    }
}
