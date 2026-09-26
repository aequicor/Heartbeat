package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackHandler
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavHost
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.Route

/**
 * A host in the navigation tree. Requests are dispatched from the caller's host upwards:
 * [NavTarget.Nearest] — the first host that [acceptsNearest]; [NavTarget.Details] — the first [PanelsHostImpl];
 * [NavTarget.Root] — the root.
 */
internal abstract class HostNode(params: HostParams) : NavHost {

    val tree: NavTree = params.tree

    /** Entry that owns this host; `null` for the root. */
    val owner: EntryNavigator? = params.owner

    /** Path in the tree: `root/<entry id>/<host name>/…`; prefix of the result addresses. */
    val path: String = params.path

    protected val log = Log.tag(LOG_TAG)
    protected val localRoutes: RouteRegistry = params.local
    protected val lookup: RouteLookup = params.local then tree.routes
    protected val context: ComponentContext = params.context

    /** Key of the Decompose router in the owner's context. */
    protected val key: String = params.key
    private val navigators = mutableMapOf<String, EntryNavigator>()

    override val backHandler: BackHandler = context.backHandler
    override val navigator: Navigator by lazy { HostNavigator(this) }

    /** Whether a [NavTarget.Nearest] request for [route] stops here. */
    abstract fun acceptsNearest(route: Route): Boolean

    /** Opens a route this host was chosen for. */
    abstract fun open(route: Route, options: NavOptions, request: ResultRequest?)

    /** Removes the entry [id]; the last entry of a nested host closes the owner entry. */
    abstract fun remove(id: String)

    /** Id of the entry the user sees on top (details over main for panels). */
    abstract fun activeEntryId(): String

    fun activeNavigator(): EntryNavigator? = navigators[activeEntryId()]

    fun dispatch(route: Route, options: NavOptions, request: ResultRequest?) {
        val hosts = generateSequence(this) { it.owner?.host }
        val target = when (options.target) {
            NavTarget.Root -> hosts.last().takeIf { it.canShow(route) }

            NavTarget.Nearest -> hosts.firstOrNull { it.acceptsNearest(route) }

            NavTarget.Details -> hosts.firstOrNull { it is PanelsHostImpl && it.canShow(route) }
                ?: hosts.firstOrNull { it.acceptsNearest(route) }
                    .also { log.w { "$path: no panels host for ${typeOf(route)}, opening in the nearest host" } }
        }
        if (target == null) {
            log.e { "$path: no host can show ${typeOf(route)} (not registered in this tree?)" }
            return
        }
        target.open(route, options, request)
    }

    fun canShow(route: Route): Boolean = lookup.forRoute(route) != null

    fun typeOf(route: Route): String = lookup.forRoute(route)?.typeName ?: route::class.simpleName.orEmpty()

    protected fun names(entries: List<Entry>): String =
        entries.joinToString(prefix = "[", postfix = "]") { typeOf(it.route) }

    protected fun requireShowable(route: Route) {
        require(canShow(route)) { "$path: route ${route::class} is not registered in this host or tree" }
    }

    /** Child factory for Decompose: navigator of the entry, lifecycle logs, cleanup of its results. */
    protected fun createChild(entry: Entry, context: ComponentContext): NavComponent {
        val navigator = EntryNavigator(this, entry)
        navigators[entry.id] = navigator
        context.lifecycle.subscribe(LifecycleLogger(navigator.path, log))
        context.lifecycle.doOnDestroy { if (navigators[entry.id] === navigator) navigators.remove(entry.id) }
        // InstanceKeeper is destroyed only when the entry leaves the stack for good, not on configuration change
        context.instanceKeeper.getOrCreate(RESULTS_CLEANUP_KEY) { ResultsCleanup(tree.results, navigator.path) }
        val routeEntry = checkNotNull(lookup.forRoute(entry.route)) {
            "$path: no route entry for ${entry.route::class}"
        }
        return routeEntry.createUnchecked(entry.route, context, navigator)
    }

    private class ResultsCleanup(private val results: ResultStore, private val path: String) : InstanceKeeper.Instance {
        override fun onDestroy() = results.dropTree(path)
    }

    private class LifecycleLogger(private val path: String, private val log: Log) : Lifecycle.Callbacks {
        override fun onCreate() = log.v { "$path created" }

        override fun onStart() = log.v { "$path started" }

        override fun onResume() = log.v { "$path resumed" }

        override fun onPause() = log.v { "$path paused" }

        override fun onStop() = log.v { "$path stopped" }

        override fun onDestroy() = log.v { "$path destroyed" }
    }

    private companion object {
        const val RESULTS_CLEANUP_KEY = "nav-results-cleanup"
    }
}

/** Construction parameters shared by every host. */
internal data class HostParams(
    val tree: NavTree,
    val owner: EntryNavigator?,
    val path: String,
    val local: RouteRegistry,
    val context: ComponentContext,
    val key: String,
)
