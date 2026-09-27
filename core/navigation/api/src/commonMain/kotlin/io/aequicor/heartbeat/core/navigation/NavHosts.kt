// childPanels is experimental in Decompose 3.5 — accepted deliberately
@file:OptIn(ExperimentalDecomposeApi::class)

package io.aequicor.heartbeat.core.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandler
import kotlin.reflect.KClass

/** An entry of a host: the configuration of a Decompose child. */
public interface NavEntry {
    /** Unique id within the host; part of the entry path used for results. */
    public val id: String

    /** The shown route. */
    public val route: Route

    /** Transition the entry was opened with. */
    public val transition: NavTransition
}

/** A host of navigation entries owned by a component. */
public interface NavHost {
    /**
     * Navigator that starts resolution in this host (e.g. a tab bar of the owner switching its nested stack).
     * Results, [Navigator.finishWithResult] and [Navigator.close] act on behalf of the owner entry.
     */
    public val navigator: Navigator

    /** Back handler of the owner component; used by the UI for predictive back. */
    public val backHandler: BackHandler

    /** Handles "back" inside the host (predictive back gesture finished). */
    public fun onBack()
}

/** A stack of entries (`childStack`). */
public interface StackHost : NavHost {
    /** The stack; the active child is on top. */
    public val stack: Value<ChildStack<NavEntry, NavComponent>>
}

/**
 * List-details layout (`childPanels`): a fixed main entry and an optional details entry. The state is the same in
 * every [ChildPanelsMode]; the UI chooses the mode by window size: [ChildPanelsMode.SINGLE] shows details over main
 * ("back" dismisses them), [ChildPanelsMode.DUAL] shows them side by side.
 */
public interface PanelsHost : NavHost {
    /** Main and details panels. */
    public val panels: Value<ChildPanels<NavEntry, NavComponent, NavEntry, NavComponent, Nothing, Nothing>>

    /** Switches the layout mode; called by the UI when the window size class changes. */
    public fun setMode(mode: ChildPanelsMode)
}

/** The root host of a navigation tree: owns the result store and handles deep links. */
public interface RootHost : StackHost {
    /** Parses [uri] and applies its commands to the tree (see [DeepLinkEntry]). */
    public fun handleDeepLink(uri: String): DeepLinkResult
}

/** Which global routes a nested stack shows itself instead of passing them up. */
public sealed interface GlobalRoutes {
    /** Only local routes; global ones go to the parent host (default). */
    public data object None : GlobalRoutes

    /** Every registered route (e.g. a stack inside a details panel that keeps navigation in the panel). */
    public data object All : GlobalRoutes

    /** Only routes of these classes. */
    public data class Only(val routes: Set<KClass<out Route>>) : GlobalRoutes
}

/**
 * Creates nested hosts inside a component. The tree (route registry, results, deep links) is inherited from
 * [parent][stack] — the navigator of the component that owns the host.
 */
public interface NavHostFactory {
    /**
     * A nested stack named [name] (unique within the owner) with the [initial] routes.
     * [local] entries are the owner's internal screens, visible only in this host.
     */
    public fun stack(
        context: ComponentContext,
        parent: Navigator,
        name: String,
        initial: List<Route>,
        local: List<RouteEntry<*>> = emptyList(),
        global: GlobalRoutes = GlobalRoutes.None,
    ): StackHost

    /**
     * A list-details host named [name] with the [main] route and optionally [details]. Requests with
     * [NavTarget.Details] from anywhere below open in its details panel; [NavTarget.Nearest] requests for
     * [local] routes do so as well.
     */
    public fun panels(
        context: ComponentContext,
        parent: Navigator,
        name: String,
        main: Route,
        details: Route? = null,
        local: List<RouteEntry<*>> = emptyList(),
    ): PanelsHost
}

/**
 * Creates a root host. Bound twice with `@ForScope`: `AppScope` — the guest tree (only app routes),
 * `ProfileScope` — the tree of a signed-in profile (profile and app routes). The platform root hosts one of them.
 */
public interface RootNavHostFactory {
    /** Creates the root named [name] (a path prefix in logs and results) with the [initial] routes. */
    public fun create(context: ComponentContext, initial: List<Route>, name: String = "root"): RootHost
}
