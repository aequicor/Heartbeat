// experimental Compose stack animations of Decompose 3.5 (shared elements, predictive back) — accepted deliberately
@file:OptIn(ExperimentalDecomposeApi::class)

package io.aequicor.heartbeat.core.navigation.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.StackAnimator
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.PredictiveBackAnimatable
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.materialPredictiveBackAnimatable
import com.arkivanov.essenty.backhandler.BackEvent
import io.aequicor.heartbeat.core.navigation.NavTransition

/**
 * Animators for each [NavTransition]. Provide another set with [LocalNavAnimations] (e.g. from the design system
 * with its motion tokens) or pass it to a host explicitly.
 */
@Immutable
public class NavAnimations(
    /** [NavTransition.Default]. */
    public val default: StackAnimator,
    /** [NavTransition.Fade]. */
    public val fade: StackAnimator,
    /** [NavTransition.Modal]. */
    public val modal: StackAnimator,
    /** [NavTransition.Expand]: only fades — the geometry comes from the shared bounds. */
    public val expand: StackAnimator,
    /** Predictive back gesture animation; `null` pops without following the gesture. */
    public val predictiveBack: ((BackEvent) -> PredictiveBackAnimatable)?,
) {
    internal fun animatorFor(transition: NavTransition): StackAnimator? = when (transition) {
        NavTransition.Default -> default
        NavTransition.Fade -> fade
        NavTransition.Modal -> modal
        is NavTransition.Expand -> expand
        NavTransition.None -> null
    }

    /** Defaults. */
    public companion object {
        /** Slide + fade, vertical slide for modals, Material predictive back. */
        public val Default: NavAnimations = NavAnimations(
            default = fade() + slide(),
            fade = fade(),
            modal = fade() + slide(orientation = Orientation.Vertical),
            expand = fade(),
            predictiveBack = { event -> materialPredictiveBackAnimatable(event) },
        )
    }
}

/** Animations used by [NavStack] and [NavPanels] by default. */
public val LocalNavAnimations: ProvidableCompositionLocal<NavAnimations> =
    staticCompositionLocalOf { NavAnimations.Default }
