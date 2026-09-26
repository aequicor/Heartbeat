package io.aequicor.heartbeat.core.navigation.compose

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.Child
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.experimental.panels.ChildPanels
import com.arkivanov.decompose.extensions.compose.experimental.stack.ChildStack
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.PredictiveBackParams
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.Direction
import com.arkivanov.decompose.extensions.compose.stack.animation.isFront
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavHost
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.PanelsHost
import io.aequicor.heartbeat.core.navigation.StackHost

/**
 * Renders a [StackHost]. The animation of a push/pop is the [NavTransition] of the entry in front (the one being
 * opened or closed), so a pop plays the reverse of its push. Supports predictive back and shared elements.
 */
@OptIn(ExperimentalDecomposeApi::class)
@Composable
public fun NavStack(
    host: StackHost,
    modifier: Modifier = Modifier,
    animations: NavAnimations = LocalNavAnimations.current,
) {
    ChildStack(
        stack = host.stack,
        modifier = modifier,
        animation = stackAnimation(
            predictiveBackParams = { host.predictiveBackParams(animations) },
            selector = { child, other, direction, _ ->
                animations.animatorFor(frontOf(child, other, direction).configuration.transition)
            },
        ),
    ) { child -> NavEntryContent(child.configuration, child.instance, animatedScope = this) }
}

/**
 * Renders a [PanelsHost] in [mode]: [ChildPanelsMode.SINGLE] on compact windows (details over main),
 * [ChildPanelsMode.DUAL] on wide ones. Choose the mode from the window size class of the design system.
 */
@OptIn(ExperimentalDecomposeApi::class)
@Composable
public fun NavPanels(
    host: PanelsHost,
    mode: ChildPanelsMode,
    modifier: Modifier = Modifier,
    animations: NavAnimations = LocalNavAnimations.current,
) {
    val panels by host.panels.subscribeAsState()
    val panelAnimations = remember(host) { NavPanelAnimations() }
    SideEffect { host.setMode(mode) } // idempotent: no-op when the mode is unchanged
    ChildPanels(
        panels = panels,
        mainChild = { NavEntryContent(it.configuration, it.instance, animatedScope = this) },
        detailsChild = { NavEntryContent(it.configuration, it.instance, animatedScope = this) },
        modifier = modifier,
        animators = panelAnimations.select(panels.details?.configuration, animations),
        predictiveBackParams = { host.predictiveBackParams(animations) },
    )
}

@OptIn(ExperimentalDecomposeApi::class)
private fun NavHost.predictiveBackParams(animations: NavAnimations): PredictiveBackParams? =
    animations.predictiveBack?.let { animatable ->
        PredictiveBackParams(backHandler = backHandler, onBack = ::onBack, animatable = animatable)
    }

private fun frontOf(
    child: Child.Created<NavEntry, NavComponent>,
    other: Child.Created<NavEntry, NavComponent>,
    direction: Direction,
): Child.Created<NavEntry, NavComponent> = if (direction.isFront) child else other

/** One entry: registers its animated scope for nested shared elements and expands from its preview if asked. */
@Composable
private fun NavEntryContent(entry: NavEntry, component: NavComponent, animatedScope: AnimatedVisibilityScope) {
    val scopes = LocalNavEntryScopes.current + animatedScope
    CompositionLocalProvider(LocalNavEntryScopes provides scopes) {
        val expand = (entry.transition as? NavTransition.Expand)?.let {
            navSharedBoundsModifier(it.sharedKey, animatedScope)
        }
        component.Render(Modifier.fillMaxSize().then(expand ?: Modifier))
    }
}
