package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

/**
 * Fills a bounded viewport and adapts its content to the measured [DpSize] on the next recomposition.
 * Content first appears after the initial measurement; this container must not size itself to its content.
 *
 * Unlike BoxWithConstraints, it never replaces the studio's wide/compact subtree during measurement.
 * Disposing a popup from a measure-time subcomposition leaves a stale render owner in Compose Desktop
 * 1.12.1's same-canvas scene ("RootNodeOwner is already disposed"). Publishing the viewport size as state
 * lets those popups close in recomposition before the next measure pass.
 */
@Composable
internal fun StudioViewport(modifier: Modifier = Modifier, content: @Composable BoxScope.(DpSize) -> Unit) {
    var measuredSize by remember { mutableStateOf<IntSize?>(null) }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().onSizeChanged { measuredSize = it }) {
        val size = measuredSize
        if (size != null) content(with(density) { size.toSize().toDpSize() })
    }
}
