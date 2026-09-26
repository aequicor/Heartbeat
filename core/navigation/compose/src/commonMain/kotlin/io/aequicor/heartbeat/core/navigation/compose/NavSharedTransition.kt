package io.aequicor.heartbeat.core.navigation.compose

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Enables shared element transitions between navigation entries (`NavTransition.Expand`).
 * Placed once by the platform root around the root host. Without it the transitions degrade to plain fades.
 */
@Composable
public fun NavSharedTransitionLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalNavSharedTransitionScope provides this) { content() }
    }
}

/**
 * Marks the element a screen expands from (a preview card): the entry opened with `NavTransition.Expand(key)`
 * grows out of these bounds and collapses back into them on pop. No-op outside [NavSharedTransitionLayout].
 */
@Composable
public fun NavSharedBounds(key: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.then(navSharedBoundsModifier(key)), propagateMinConstraints = true) { content() }
}

/** Shared transition scope of [NavSharedTransitionLayout], `null` outside it. */
public val LocalNavSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?> =
    staticCompositionLocalOf { null }

/** Animated visibility scopes of the entries containing the current composition, outermost first. */
internal val LocalNavEntryScopes: ProvidableCompositionLocal<List<AnimatedVisibilityScope>> =
    compositionLocalOf { emptyList() }

/**
 * Shared bounds in the scope that actually animates. A preview inside a nested host sits in several entries;
 * the one being replaced is the innermost entry whose transition runs (a static nested entry would never match).
 */
@Composable
internal fun navSharedBoundsModifier(key: String, scope: AnimatedVisibilityScope? = null): Modifier {
    val shared = LocalNavSharedTransitionScope.current ?: return Modifier
    val scopes = LocalNavEntryScopes.current
    val animated = scope
        ?: scopes.lastOrNull { it.transition.currentState != it.transition.targetState }
        ?: scopes.lastOrNull()
        ?: return Modifier
    return with(shared) { Modifier.sharedBounds(rememberSharedContentState(key), animated) }
}
