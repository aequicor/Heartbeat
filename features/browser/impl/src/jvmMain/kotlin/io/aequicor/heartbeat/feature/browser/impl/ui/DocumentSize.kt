package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface

/**
 * Intrinsic presentation obeys the current host's constraints: tight Start constraints fill it, while a loose
 * modal can shrink and grow up to its remaining height. Registration-driven inline documents keep their presets.
 */
internal fun Modifier.documentSize(
    surface: BrowserDocumentSurface,
    intrinsicHeight: Dp,
    dimensions: HbDimensions,
): Modifier = when {
    surface.isFill -> fillMaxSize()

    surface.isHostContentSized -> height(intrinsicHeight)

    surface.isContentSized -> height(
        intrinsicHeight.coerceIn(dimensions.webDocumentMinHeight, dimensions.webDocumentMaxHeight),
    )

    else -> this
}
