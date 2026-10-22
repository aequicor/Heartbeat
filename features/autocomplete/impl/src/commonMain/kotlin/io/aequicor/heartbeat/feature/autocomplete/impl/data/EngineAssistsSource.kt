package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = Log.tag("Autocomplete/EngineAssists")

/**
 * Native listing of one route. [hasNativeCatalog] is true whenever the engine owns skill discovery, including an
 * empty, blocked, failed or still-loading listing: only an engine without the feature leaves discovery to the host.
 */
internal data class NativeAssists(val items: List<EngineAssist>, val hasNativeCatalog: Boolean) {
    internal companion object {
        /** Unknown or closed listing; the host must not substitute its own skills for it. */
        val Closed = NativeAssists(emptyList(), hasNativeCatalog = true)

        /** The engine has no native discovery. */
        val Unsupported = NativeAssists(emptyList(), hasNativeCatalog = false)
    }
}

/**
 * Native engine assists of the composer, resolved through the facade without starting a session. Listings are
 * cached per engine route and workspace for a short time: typing must not hammer a native runtime, and a
 * failure degrades to the Heartbeat-only sections instead of blocking the popup.
 */
@Inject
internal class EngineAssistsSource(
    private val facade: EngineFacade,
    private val clock: Clock,
    private val sources: AuthSources,
    private val configuration: EngineConfiguration = UnavailableEngineConfiguration(),
    private val workspaces: LocalWorkspaces? = null,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<Route, Entry>()

    /** Drops stale rows before reloading when trust, engine availability or binding permissions change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(target: EngineTarget, workspace: WorkspaceRef?): Flow<NativeAssists> = combine(
        configuration.state,
        facade.bindings.state,
        facade.engines.state,
        sources.state,
    ) { config, bindings, engines, auth ->
        listOf(config, bindings, engines, auth)
    }.transformLatest {
        emit(NativeAssists.Closed)
        emit(assists(target, workspace))
    }

    private fun stamp(): List<Any> = listOf(
        configuration.state.value,
        facade.bindings.state.value,
        facade.engines.state.value,
        sources.state.value,
    )

    /** Native assists visible on [target]; a null [workspace] is a chat without a project. */
    internal suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): NativeAssists {
        val route = Route(target.engine.value, target.binding.value, workspace?.value)
        val now = clock.now()
        val stamp = stamp()
        mutex.withLock {
            cache[route]?.takeIf { now - it.fetchedAt < TTL && it.stamp == stamp }?.let { return it.assists }
        }
        val assists = read(target, workspace)
        return mutex.withLock {
            if (stamp() != stamp) {
                NativeAssists.Closed
            } else {
                if (cache.size >= CACHE_LIMIT) cache.remove(cache.keys.first())
                cache[route] = Entry(now, assists, stamp)
                assists
            }
        }
    }

    /**
     * Whether [target]'s engine owns skill discovery, decided by its declared feature alone: no listing runs, and an
     * engine whose feature is present but blocked still counts as a catalog.
     */
    internal suspend fun hasNativeCatalog(target: EngineTarget, workspace: WorkspaceRef?): Boolean =
        !isHostDelivered(target, workspace) &&
            facade.engines.features(target.engine).resolve(ListsComposerAssists) != FeatureAccess.Unsupported

    /**
     * A native process of a virtual workspace starts in an empty directory and sees no project skills unless its
     * adapter imports them from the virtual tree; elsewhere the host delivers them like for an engine without a
     * catalog. An unreadable workspace keeps the native route, which then fails closed.
     */
    private suspend fun isHostDelivered(target: EngineTarget, workspace: WorkspaceRef?): Boolean {
        val ref = workspace ?: return false
        val isVirtual = try {
            workspaces?.virtual(ref) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "workspace kind unknown for composer assists" }
            false
        }
        val descriptor = facade.engines.state.value.firstOrNull { it.descriptor.id == target.engine }?.descriptor
        return isVirtual && descriptor?.areVirtualWorkspaceAssistsNative != true
    }

    private suspend fun read(target: EngineTarget, workspace: WorkspaceRef?): NativeAssists =
        if (isHostDelivered(target, workspace)) NativeAssists.Unsupported else readNative(target, workspace)

    private suspend fun readNative(target: EngineTarget, workspace: WorkspaceRef?): NativeAssists =
        when (val resolved = facade.engines.features(target.engine).resolve(ListsComposerAssists)) {
            is FeatureAccess.Available -> try {
                NativeAssists(resolved.feature.assists(target, workspace), hasNativeCatalog = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "native composer assists unavailable for engine=${target.engine.value}" }
                NativeAssists.Closed
            }

            is FeatureAccess.Unavailable -> {
                log.w { "native composer assists blocked for engine=${target.engine.value}" }
                NativeAssists.Closed
            }

            FeatureAccess.Unsupported -> NativeAssists.Unsupported
        }

    private data class Route(val engine: String, val binding: String, val workspace: String?)

    private data class Entry(val fetchedAt: kotlin.time.Instant, val assists: NativeAssists, val stamp: List<Any>)

    private companion object {
        const val CACHE_LIMIT = 32
        val TTL: Duration = 60.seconds
    }
}
