package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Determinate usage with a rounded track, shared across all platform kits to retain the flat studio style.
 * [progress] must be finite and is clamped to 0–1 for both drawing and accessibility. Unknown data should
 * omit the component; it must not be represented by zero or indeterminate animation.
 */
@Composable
public fun HbProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    color: Color = HbTheme.colors.brand,
) {
    require(progress.isFinite()) { "Progress must be finite" }
    val fraction = progress.coerceIn(0f, 1f)
    val track = HbTheme.colors.outlineSubtle
    Canvas(
        modifier.fillMaxWidth().height(HbTheme.dimensions.usageBarHeight).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            if (contentDescription != null) this.contentDescription = contentDescription
        },
    ) {
        val corner = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = corner)
        if (fraction > 0f) drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = corner)
    }
}
