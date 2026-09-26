package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/** Finds the [RouteEntry] of a route by its class or by its stable type name. */
internal interface RouteLookup {
    fun forRoute(route: Route): RouteEntry<*>?

    fun forType(typeName: String): RouteEntry<*>?
}

/** Immutable set of route entries; duplicate classes or type names are a wiring error. */
internal class RouteRegistry(entries: Collection<RouteEntry<*>>) : RouteLookup {

    private val byClass: Map<KClass<*>, RouteEntry<*>> = entries.associateByUnique("route class") { it.routeClass }
    private val byType: Map<String, RouteEntry<*>> = entries.associateByUnique("route type name") { it.typeName }

    fun contains(routeClass: KClass<out Route>): Boolean = routeClass in byClass

    override fun forRoute(route: Route): RouteEntry<*>? = byClass[route::class]

    override fun forType(typeName: String): RouteEntry<*>? = byType[typeName]

    /** Lookup that prefers this registry and falls back to [fallback]. */
    infix fun then(fallback: RouteLookup): RouteLookup = object : RouteLookup {
        override fun forRoute(route: Route) = this@RouteRegistry.forRoute(route) ?: fallback.forRoute(route)

        override fun forType(typeName: String) = this@RouteRegistry.forType(typeName) ?: fallback.forType(typeName)
    }

    companion object {
        val Empty = RouteRegistry(emptyList())
    }
}

private fun <K : Any> Collection<RouteEntry<*>>.associateByUnique(
    what: String,
    key: (RouteEntry<*>) -> K,
): Map<K, RouteEntry<*>> = groupBy(key).mapValues { (k, same) ->
    require(same.size == 1) { "duplicate $what $k: ${same.joinToString { it::class.toString() }}" }
    same.single()
}

// RouteEntry<R> is matched to a route by its exact class (RouteRegistry.byClass), so the route is an R.
@Suppress("UNCHECKED_CAST") // safe: the entry was looked up by the route's own class
internal fun RouteEntry<*>.createUnchecked(
    route: Route,
    context: ComponentContext,
    navigator: Navigator,
): NavComponent = (this as RouteEntry<Route>).create(route, context, navigator)

@Suppress("UNCHECKED_CAST") // safe: the entry was looked up by the route's own class
internal val RouteEntry<*>.routeSerializer: KSerializer<Route>
    get() = serializer as KSerializer<Route>
