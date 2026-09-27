package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions

private const val ILLUSTRATION_WIDTH = 240f
private const val ILLUSTRATION_HEIGHT = 180f
private const val ILLUSTRATION_STROKE = 1.6f

/** Theme-aware scenes for common product states. Geometry is shared by every platform kit. */
public enum class HbIllustrationKind {
    Welcome,
    EmptyWorkspace,
    EmptyChat,
    NoResults,
    Success,
    Error,
    Offline,
    Upload,
}

/**
 * Scalable vector artwork with semantic theme colors and no motion.
 * Pass null when adjacent text already explains the state; otherwise supply a localized description.
 * The illustration is decorative: actions and state copy belong to the containing screen.
 */
@Composable
public fun HbIllustration(
    illustration: HbIllustrationKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val colors = HbTheme.colors
    val vector = remember(illustration, colors) { illustrationVector(illustration, colors) }
    Image(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(HbTheme.dimensions.illustrationWidth, HbTheme.dimensions.illustrationHeight),
    )
}

internal fun illustrationVector(kind: HbIllustrationKind, colors: HbColors): ImageVector {
    val artwork = kind.artwork()
    val dimensions = HbDimensions()
    val accent = when (kind) {
        HbIllustrationKind.Success -> colors.success

        HbIllustrationKind.Error -> colors.error

        HbIllustrationKind.Welcome,
        HbIllustrationKind.EmptyWorkspace,
        HbIllustrationKind.EmptyChat,
        HbIllustrationKind.NoResults,
        HbIllustrationKind.Offline,
        HbIllustrationKind.Upload,
        -> colors.primary
    }
    return ImageVector.Builder(
        name = kind.name,
        defaultWidth = dimensions.illustrationWidth,
        defaultHeight = dimensions.illustrationHeight,
        viewportWidth = ILLUSTRATION_WIDTH,
        viewportHeight = ILLUSTRATION_HEIGHT,
    ).apply {
        illustrationPath(
            "M40,101 C29,65 62,26 103,33 C142,8 207,38 201,89 " +
                "C222,132 172,157 132,147 C87,164 32,145 40,101 Z",
            fill = colors.primaryContainer,
        )
        illustrationPath(
            "M36,150 H204 M39,57 H47 M43,53 V61 M196,117 H204 M200,113 V121",
            stroke = colors.outlineSubtle,
        )
        illustrationPath(artwork.back, fill = colors.surfaceElevated, stroke = colors.outlineSubtle)
        illustrationPath(artwork.paper, fill = colors.assistantSurface, stroke = colors.textSecondary)
        illustrationPath(artwork.accent, fill = accent, stroke = colors.accessibleIllustrationInk(kind))
        illustrationPath(artwork.detail, stroke = colors.textSecondary)
        illustrationPath(artwork.accentDetail, stroke = colors.accessibleIllustrationInk(kind))
    }.build()
}

private fun HbColors.accessibleIllustrationInk(kind: HbIllustrationKind): Color = when (kind) {
    HbIllustrationKind.Success -> onSuccess

    HbIllustrationKind.Error -> onError

    HbIllustrationKind.Welcome,
    HbIllustrationKind.EmptyWorkspace,
    HbIllustrationKind.EmptyChat,
    HbIllustrationKind.NoResults,
    HbIllustrationKind.Offline,
    HbIllustrationKind.Upload,
    -> onPrimary
}

private fun ImageVector.Builder.illustrationPath(path: String, fill: Color? = null, stroke: Color? = null) {
    if (path.isEmpty()) return
    addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = fill?.let(::SolidColor),
        stroke = stroke?.let(::SolidColor),
        strokeLineWidth = ILLUSTRATION_STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}
