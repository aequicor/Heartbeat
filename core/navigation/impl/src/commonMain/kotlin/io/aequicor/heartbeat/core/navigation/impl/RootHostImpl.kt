package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.core.navigation.DeepLinkResult
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route

/** Root of a navigation tree: shows every global route and applies deep links. */
internal class RootHostImpl(tree: NavTree, name: String, context: ComponentContext, initial: List<Route>) :
    StackHostImpl(
        params = HostParams(tree, owner = null, path = name, local = RouteRegistry.Empty, context, key = "nav:$name"),
        global = GlobalRoutes.All,
        initial = initial,
    ),
    RootHost {

    override fun acceptsNearest(route: Route): Boolean = tree.routes.contains(route::class)

    override fun handleDeepLink(uri: String): DeepLinkResult = when (val link = tree.deepLinks.resolve(uri)) {
        is DeepLinkRouter.Resolution.Rejected -> {
            log.w { "$path: deep link rejected: ${link.reason}" }
            DeepLinkResult.Rejected
        }

        DeepLinkRouter.Resolution.NoMatch -> {
            log.i { "$path: deep link has no match in this tree" }
            DeepLinkResult.NoMatch
        }

        is DeepLinkRouter.Resolution.Matched -> apply(link)
    }

    private fun apply(link: DeepLinkRouter.Resolution.Matched): DeepLinkResult {
        val commands = try {
            link.entry.commands(link.params)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "$path: deep link '${link.entry.pattern}' rejected: invalid parameters" }
            return DeepLinkResult.Rejected
        }
        log.i { "$path: deep link '${link.entry.pattern}' -> ${commands.size} command(s)" }
        // Decompose creates children synchronously: after each command the hosts it opened already exist,
        // so the next command starts from the deepest active entry.
        commands.forEach { command -> deepestNavigator().navigate(command.route, command.options) }
        return DeepLinkResult.Handled
    }

    private fun deepestNavigator(): Navigator {
        var current: EntryNavigator = activeNavigator() ?: return navigator
        while (true) {
            current = current.hosts.lastOrNull()?.activeNavigator() ?: return current
        }
    }
}
