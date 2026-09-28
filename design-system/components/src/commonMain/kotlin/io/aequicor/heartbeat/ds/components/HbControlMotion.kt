package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Interactive surfaces remain flat in every visual style; callers animate their semantic state layer. */
internal fun Modifier.hbControlSurface(background: Color, shape: Shape): Modifier = background(background, shape)

/** Dense desktop targets never shrink mobile hit areas. */
@Composable
@ReadOnlyComposable
internal fun controlTargetSize(requested: Dp): Dp {
    val dimensions = HbTheme.dimensions
    val minimum = if (dimensions.touchTarget > dimensions.controlHeight) {
        dimensions.touchTarget
    } else {
        dimensions.compactControlHeight
    }
    return maxOf(requested, minimum)
}
