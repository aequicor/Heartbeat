package io.aequicor.heartbeat.ds.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact spacing scale on a two-point grid. */
@Immutable
data class HbSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 6.dp,
    val m: Dp = 8.dp,
    val l: Dp = 12.dp,
    val xl: Dp = 16.dp,
    val xxl: Dp = 20.dp,
)

/** Reusable container geometry. */
@Immutable
data class HbShapes(
    val small: RoundedCornerShape = RoundedCornerShape(6.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(10.dp),
    val large: RoundedCornerShape = RoundedCornerShape(14.dp),
)

/** Surface elevation levels. */
@Immutable
data class HbElevation(val none: Dp = 0.dp, val low: Dp = 1.dp, val medium: Dp = 4.dp, val high: Dp = 8.dp)

/** Responsive application, chat and interaction dimensions. */
@Immutable
data class HbDimensions(
    val touchTarget: Dp = 48.dp,
    val controlHeight: Dp = 32.dp,
    val switchWidth: Dp = 36.dp,
    val switchHeight: Dp = 20.dp,
    val contentMaxWidth: Dp = 1200.dp,
    val chatMessageMaxWidth: Dp = 720.dp,
    val sidebarWidth: Dp = 192.dp,
    val windowWidth: Dp = 1280.dp,
    val windowHeight: Dp = 900.dp,
    val compactBreakpoint: Dp = 720.dp,
    val compactHeightBreakpoint: Dp = 640.dp,
    val expandedBreakpoint: Dp = 1200.dp,
    val borderWidth: Dp = 1.dp,
    val chatAvatarSize: Dp = 32.dp,
    val composerMinHeight: Dp = 56.dp,
    val composerMaxHeight: Dp = 120.dp,
    val composerEditorMinHeight: Dp = 40.dp,
    val composerMenuMaxHeight: Dp = 320.dp,
    val composerMenuMinWidth: Dp = 240.dp,
    val composerMenuMaxWidth: Dp = 360.dp,
    val composerMenuGutter: Dp = 16.dp,
    val composerMenuOffset: Dp = 8.dp,
    val glassBlurRadius: Dp = 16.dp,
    val glassShadowRadius: Dp = 12.dp,
    val glassShadowOffset: Dp = 3.dp,
    val transcriptEdgeFade: Dp = 32.dp,
    val toolPayloadMaxHeight: Dp = 320.dp,
    val markdownTableCellWidth: Dp = 200.dp,
    val swatchSize: Dp = 64.dp,
    val scrollbarThickness: Dp = 4.dp,
    val scrollbarHoverThickness: Dp = 6.dp,
    val scrollbarHoverWidth: Dp = 12.dp,
    val scrollbarMinThumb: Dp = 28.dp,
    val scrollbarInset: Dp = 2.dp,
    val scrollbarLazyItemExtent: Dp = 40.dp,
    val scrollbarMaxThumbFraction: Float = 0.8f,
    val iconSize: Dp = 18.dp,
    val iconSmallSize: Dp = 14.dp,
    val iconLargeSize: Dp = 24.dp,
    val iconTileWidth: Dp = 112.dp,
    val illustrationWidth: Dp = 240.dp,
    val illustrationHeight: Dp = 180.dp,
)

/** Animation timing; consumers may opt out of decorative movement. */
@Immutable
data class HbMotion(
    val fastMillis: Int = 120,
    val normalMillis: Int = 220,
    val slowMillis: Int = 360,
    val copyFeedbackMillis: Int = 1800,
    val isReducedMotion: Boolean = false,
    val scrollbarHideDelayMillis: Int = 700,
    val scrollbarFadeMillis: Int = 180,
)
