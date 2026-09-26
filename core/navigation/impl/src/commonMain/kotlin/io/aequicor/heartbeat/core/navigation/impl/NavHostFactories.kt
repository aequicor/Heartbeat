package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.PanelsHost
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.RootNavHostFactory
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.navigation.StackHost

@ContributesBinding(AppScope::class)
@Inject
internal class NavHostFactoryImpl : NavHostFactory {

    private val log = Log.tag(LOG_TAG)

    override fun stack(
        context: ComponentContext,
        parent: Navigator,
        name: String,
        initial: List<Route>,
        local: List<RouteEntry<*>>,
        global: GlobalRoutes,
    ): StackHost {
        val owner = parent.asOwner()
        val host = StackHostImpl(owner.hostParams(context, name, local), global, initial)
        return host.also { attach(owner, it, context) }
    }

    override fun panels(
        context: ComponentContext,
        parent: Navigator,
        name: String,
        main: Route,
        details: Route?,
        local: List<RouteEntry<*>>,
    ): PanelsHost {
        val owner = parent.asOwner()
        val host = PanelsHostImpl(owner.hostParams(context, name, local), main, details)
        return host.also { attach(owner, it, context) }
    }

    private fun attach(owner: EntryNavigator, host: HostNode, context: ComponentContext) {
        owner.hosts += host
        context.lifecycle.doOnDestroy { owner.hosts -= host }
        log.d { "${host.path}: host created" }
    }

    private fun Navigator.asOwner(): EntryNavigator = requireNotNull(this as? EntryNavigator) {
        "parent must be the navigator passed to the component by its RouteEntry, got ${this::class}"
    }

    private fun EntryNavigator.hostParams(context: ComponentContext, name: String, local: List<RouteEntry<*>>) =
        HostParams(host.tree, owner = this, path = "$path/$name", RouteRegistry(local), context, key = "nav:$name")
}

/** Root factory over given registries, bound per scope by [GuestRootNavHostFactory], [ProfileRootNavHostFactory]. */
internal class RootNavHostFactoryImpl(private val routes: RouteRegistry, private val deepLinks: DeepLinkRouter) :
    RootNavHostFactory {

    private val log = Log.tag(LOG_TAG)

    override fun create(context: ComponentContext, initial: List<Route>, name: String): RootHost {
        val key = "$RESULTS_KEY:$name"
        val results = context.instanceKeeper.getOrCreate(key) {
            ResultStore(context.stateKeeper.consume(key, SavedResults.serializer()))
        }
        // registered on every root instance: after a configuration change the StateKeeper is new, the store is retained
        if (!context.stateKeeper.isRegistered(key)) {
            context.stateKeeper.register(key, SavedResults.serializer()) { results.snapshot() }
        }
        val tree = NavTree(routes, deepLinks, results)
        return RootHostImpl(tree, name, context, initial).also { log.i { "$name: root created" } }
    }

    private companion object {
        const val RESULTS_KEY = "nav-results"
    }
}
