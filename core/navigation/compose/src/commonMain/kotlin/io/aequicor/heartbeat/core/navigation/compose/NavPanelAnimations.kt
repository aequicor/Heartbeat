// ChildPanels animator selectors are experimental in Decompose 3.5 — ADR-0003.
@file:OptIn(ExperimentalDecomposeApi::class)

package io.aequicor.heartbeat.core.navigation.compose

import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.experimental.panels.ChildPanelsAnimators
import com.arkivanov.decompose.extensions.compose.stack.animation.Direction
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavTransition

/** Keeps the details transition available to the main panel after details have been dismissed. */
internal class NavPanelAnimations {

    private var lastDetailsTransition: NavTransition = NavTransition.Default

    fun select(
        details: NavEntry?,
        animations: NavAnimations,
    ): ChildPanelsAnimators<NavEntry, NavComponent, NavEntry, NavComponent, Nothing, Nothing> {
        details?.let { lastDetailsTransition = it.transition }
        val transition = lastDetailsTransition
        return ChildPanelsAnimators(
            main = { _, mode, _, _ ->
                if (mode == ChildPanelsMode.SINGLE) animations.animatorFor(transition) else null
            },
            details = { child, _, direction, _ ->
                // A replacement pushes the old details behind the new ones; both use the incoming transition.
                // On pop, the outgoing details keep the transition they were opened with.
                animations.animatorFor(
                    if (direction == Direction.EXIT_BACK) transition else child.configuration.transition,
                )
            },
        )
    }
}
