package io.aequicor.heartbeat.core.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a navigation request is applied. */
public data class NavOptions(
    /** What happens to the target stack. */
    val launch: LaunchMode = LaunchMode.Push,
    /** Which host opens the route. */
    val target: NavTarget = NavTarget.Nearest,
    /** Animation of this entry; saved with the entry, so popping plays the matching reverse animation. */
    val transition: NavTransition = NavTransition.Default,
)

/** Effect on the target stack. Routes are compared with `equals`. */
public enum class LaunchMode {
    /** Adds the route on top. */
    Push,

    /** Adds the route unless the top entry already shows an equal route. */
    SingleTop,

    /** Moves the existing entry with an equal route to the top (keeping its component), or adds it. */
    BringToFront,

    /** Replaces the top entry. */
    ReplaceCurrent,

    /** Replaces the whole stack. */
    ReplaceAll,
}

/** Which host handles a navigation request. */
public enum class NavTarget {
    /**
     * The nearest host (from the caller up to the root) that can show the route: a host always shows its local
     * routes; global routes — only the root and nested hosts that accept them (`GlobalRoutes`).
     */
    Nearest,

    /** The root host: full screen over everything. */
    Root,

    /** The details panel of the nearest panels host (next to the list on tablets, over it on phones). */
    Details,
}

/** Transition of a stack entry. Rendered by `core:navigation:compose`. */
@Serializable
public sealed interface NavTransition {
    /** Platform default: slide with fade. */
    @Serializable
    @SerialName("default")
    public data object Default : NavTransition

    /** No animation. */
    @Serializable
    @SerialName("none")
    public data object None : NavTransition

    /** Cross-fade. */
    @Serializable
    @SerialName("fade")
    public data object Fade : NavTransition

    /** Slides up from the bottom (modal flows). */
    @Serializable
    @SerialName("modal")
    public data object Modal : NavTransition

    /**
     * Container transform: the screen expands from the element marked with the same [sharedKey]
     * (`NavSharedBounds(key)` in the caller's UI) and collapses back into it on pop.
     * The opened feature knows nothing about it. Use keys like `"chat:$chatId"`.
     */
    @Serializable
    @SerialName("expand")
    public data class Expand(val sharedKey: String) : NavTransition
}
