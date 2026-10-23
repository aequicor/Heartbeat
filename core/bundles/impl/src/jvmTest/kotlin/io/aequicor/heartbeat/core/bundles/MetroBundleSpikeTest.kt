package io.aequicor.heartbeat.core.bundles

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.spike.FixtureService
import io.aequicor.heartbeat.core.bundles.spike.LibraryFixtureService
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Marker of the spike extension scope. */
abstract class SpikeChildScope private constructor()

/** A service another bundle exports. */
class Heavy

/** Cross-bundle imports of the spike bundle; the host backs it with a [Proxy]. */
interface SpikeImports {
    val heavy: Heavy
}

/** Uses an import eagerly. */
@Inject
class EagerUser(val heavy: Heavy)

/** Uses an import through a [Provider]. */
@Inject
class LazyUser(val heavy: Provider<Heavy>)

@DependencyGraph(
    AppScope::class,
    excludes = [ProfileGraph.Factory::class, LibraryFixtureService::class, BundleFixtureService::class],
)
interface SpikeAppGraph {
    val eager: EagerUser
    val lazyUser: LazyUser
    val child: SpikeChildGraph.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Includes imports: SpikeImports,
        ): SpikeAppGraph
    }
}

@SingleIn(SpikeChildScope::class)
@Inject
class ChildUser(val heavy: Heavy)

@GraphExtension(SpikeChildScope::class)
interface SpikeChildGraph {
    val user: ChildUser

    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes imports: SpikeImports,
        ): SpikeChildGraph
    }
}

/**
 * Spike of the Metro 1.4.5 behaviour the per-bundle graph design relies on (confirmed by these tests):
 * - `@Includes` accessors backed by a `java.lang.reflect.Proxy` are called only when a dependent binding is built,
 *   and not at all behind a `Provider`, so import cycles resolve lazily;
 * - `@Includes` works on a `@GraphExtension.Factory` (profile-level bundle graphs);
 * - `excludes` removes a public `@ContributesTo` contribution of a non-impl library (`ProfileGraph.Factory`);
 * - an `internal` contribution of a non-impl library (`DefaultDispatcherProvider`) cannot be named in `excludes`;
 *   a `@ContributesBinding(priority = 0)` in the bundle overrides it, the mechanism the repo already uses.
 */
class MetroBundleSpikeTest {

    private class CountingImports {
        var calls = 0
        val heavy = Heavy()
        val proxy: SpikeImports = Proxy.newProxyInstance(
            SpikeImports::class.java.classLoader,
            arrayOf(SpikeImports::class.java),
        ) { _, method, _ ->
            if (method.name == "getHeavy") {
                calls++
                heavy
            } else {
                error("unexpected ${method.name}")
            }
        } as SpikeImports
    }

    @Test
    fun `included Proxy accessor is not called when the graph is created`() {
        val imports = CountingImports()
        createGraphFactory<SpikeAppGraph.Factory>().create(imports.proxy)
        assertEquals(0, imports.calls, "creating the graph must not resolve imports")
    }

    @Test
    fun `included Proxy accessor is resolved when a dependent binding is requested`() {
        val imports = CountingImports()
        val graph = createGraphFactory<SpikeAppGraph.Factory>().create(imports.proxy)
        assertEquals(0, imports.calls)
        assertSame(imports.heavy, graph.eager.heavy)
        assertTrue(imports.calls >= 1)
    }

    @Test
    fun `Provider of an import defers the Proxy call until get`() {
        val imports = CountingImports()
        val graph = createGraphFactory<SpikeAppGraph.Factory>().create(imports.proxy)
        val user = graph.lazyUser
        assertEquals(0, imports.calls, "Provider injection must not resolve the import")
        assertSame(imports.heavy, user.heavy())
        assertEquals(1, imports.calls)
    }

    @Test
    fun `Includes works on a graph extension factory`() {
        val imports = CountingImports()
        val app = createGraphFactory<SpikeAppGraph.Factory>().create(CountingImports().proxy)
        val child = app.child.create(imports.proxy)
        assertSame(imports.heavy, child.user.heavy)
    }

    @Test
    fun `excludes removes a public ContributesBinding of another module`() {
        val graph = createGraph<SpikeExcludedBindingGraph>()
        assertEquals("bundle", graph.service.origin)
    }

    @Test
    fun `higher priority contribution overrides an internal foundation contribution`() {
        val graph = createGraph<SpikeDispatcherGraph>()
        assertTrue(graph.dispatchers is SpikeDispatcherOverride)
    }
}

/** Bundle-local override of a foundation contribution that is `internal` and so cannot be named in `excludes`. */
@dev.zacsweers.metro.ContributesBinding(AppScope::class, priority = 0)
@Inject
class SpikeDispatcherOverride : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
}

/** Bundle-local binding that replaces the library one; without `excludes` the two would clash at compile time. */
@dev.zacsweers.metro.ContributesBinding(AppScope::class)
@Inject
class BundleFixtureService : FixtureService {
    override val origin: String = "bundle"
}

@DependencyGraph(AppScope::class, excludes = [ProfileGraph.Factory::class, LibraryFixtureService::class])
interface SpikeExcludedBindingGraph {
    val service: FixtureService
}

@DependencyGraph(
    AppScope::class,
    excludes = [ProfileGraph.Factory::class, LibraryFixtureService::class, BundleFixtureService::class],
)
interface SpikeDispatcherGraph {
    val dispatchers: DispatcherProvider
}
