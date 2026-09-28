package io.aequicor.heartbeat.ds.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact spacing scale on a two-point grid. */
@Immutable
data class HbSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 6.dp,
    val m: Dp = 8.dp,
    val l: Dp = 12.dp,
    val xl: Dp = 16.dp,
    val xxl: Dp = 20.dp,
)

/**
 * Reusable container geometry. Radii stay small: the flat studio style has no 20–24dp decorative corners.
 * [Mobile] rounds touch surfaces slightly more than the dense [Desktop] preset.
 */
@Immutable
data class HbShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val large: RoundedCornerShape = RoundedCornerShape(16.dp),
) {
    /** Host presets chosen by the theme together with [HbDimensions]. */
    companion object {
        val Mobile = HbShapes()
        val Desktop = HbShapes(
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
        )
    }
}

/** Surface elevation levels. */
@Immutable
data class HbElevation(val none: Dp = 0.dp, val low: Dp = 1.dp, val medium: Dp = 4.dp, val high: Dp = 8.dp)

/**
 * The single set of application, chat and interaction dimensions. [Mobile] keeps touch targets of at least
 * 44–48dp; [Desktop] and [DesktopMacOs] are the dense mouse presets of the studio (44dp header, 28dp rows,
 * 28–32dp controls, content column of at most 760dp). The theme picks the preset by host, never by native kit.
 */
@Immutable
data class HbDimensions(
    val touchTarget: Dp = 48.dp,
    val controlHeight: Dp = 32.dp,
    val compactControlHeight: Dp = 28.dp,
    val switchWidth: Dp = 36.dp,
    val switchHeight: Dp = 20.dp,
    val contentMaxWidth: Dp = 1200.dp,
    val chatMessageMaxWidth: Dp = 720.dp,
    /** Icon rail of an application shell. */
    val navigationRailWidth: Dp = 56.dp,
    /** Navigation panel listing projects and conversations next to the content. */
    val navigationPanelWidth: Dp = 256.dp,
    /** Upper bound of a navigation drawer on compact screens. */
    val drawerMaxWidth: Dp = 320.dp,
    /** Side inspector next to a chat column, e.g. research questions and sources. */
    val inspectorPanelWidth: Dp = 320.dp,
    /** Narrowest readable content pane; side-by-side panes need at least two. */
    val paneMinWidth: Dp = 360.dp,
    /** Small status dot, e.g. unread content in a navigation row. */
    val statusDotSize: Dp = 8.dp,
    val windowWidth: Dp = 1280.dp,
    val windowHeight: Dp = 900.dp,
    val compactBreakpoint: Dp = 720.dp,
    val compactHeightBreakpoint: Dp = 640.dp,
    val expandedBreakpoint: Dp = 1200.dp,
    val borderWidth: Dp = 1.dp,
    /** Interactive geometry is independent of decorative panel and message shapes. */
    val controlCornerRadius: Dp = 6.dp,
    val fieldCornerRadius: Dp = 8.dp,
    val focusOutset: Dp = 2.dp,
    val macFocusWidth: Dp = 3.dp,
    val fluentFocusOuterWidth: Dp = 2.dp,
    val fluentFocusInnerWidth: Dp = 1.dp,
    val fieldFocusWidth: Dp = 2.dp,
    val tooltipMaxWidth: Dp = 320.dp,
    val chatAvatarSize: Dp = 32.dp,
    val composerMinHeight: Dp = 56.dp,
    val composerMaxHeight: Dp = 120.dp,
    val composerEditorMinHeight: Dp = 40.dp,
    val composerMenuMaxHeight: Dp = 320.dp,
    val composerMenuMinWidth: Dp = 240.dp,
    val composerMenuMaxWidth: Dp = 360.dp,
    val composerMenuGutter: Dp = 16.dp,
    val composerMenuOffset: Dp = 8.dp,
    /** Blur and vertical offset of [HbColors.popupShadow]. */
    val popupShadowRadius: Dp = 12.dp,
    val popupShadowOffset: Dp = 4.dp,
    val transcriptEdgeFade: Dp = 32.dp,
    /** Width of the trailing fade that replaces an ellipsis on clipped single-line text. */
    val textOverflowFade: Dp = 24.dp,
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
    /** Actual host density, independent of which native control kit is previewed. */
    val isDesktop: Boolean = false,
    val railWidth: Dp = 72.dp,
    /** Width of the window sidebar: studio sessions or settings sections. */
    val sidebarWidth: Dp = 240.dp,
    val avatarSize: Dp = 44.dp,
    val headerAvatarSize: Dp = 36.dp,
    /** Corner of content panels such as a compact drawer. */
    val cornerRadius: Dp = 16.dp,
    val outerInset: Dp = 0.dp,
    val verticalInset: Dp = 0.dp,
    val panelGap: Dp = 0.dp,
    /** Height of a window or pane header. */
    val headerHeight: Dp = 56.dp,
    /** Reading column of conversations and settings sections. */
    val messageMaxWidth: Dp = 1000.dp,
    val composerMaxWidth: Dp = 1090.dp,
    val navigationHeaderTopInset: Dp = 48.dp,
    val sidebarHeadingHeight: Dp = 32.dp,
    val messagePadding: Dp = 24.dp,
    /** Minimum inner answer width before prose shares the author's inset beside the avatar. */
    val messageBodyIndentMinWidth: Dp = 560.dp,
    val messageCornerRadius: Dp = 16.dp,
    val toolPadding: Dp = 12.dp,
    val composerActionSize: Dp = 56.dp,
    val composerPillHeight: Dp = 44.dp,
    val composerMinEditorWidth: Dp = 160.dp,
    /** Largest toolbar label before its fade; the full label remains available in a tooltip. */
    val composerLabelMaxWidth: Dp = 160.dp,
    /** Horizontal exclusion zone for native traffic lights when content reaches the window edge. */
    val titlebarLeadingInset: Dp = 96.dp,
    val composerInlineBreakpoint: Dp = 720.dp,
    val markStroke: Dp = 2.dp,
    /** Row of a navigation list: sessions, projects, settings sections. */
    val navigationRowHeight: Dp = 48.dp,
    /** Vertical space reserved above sidebar contents for native macOS window controls. */
    val titlebarInset: Dp = 48.dp,
    /** Eight desktop body lines at the standard font scale; mobile keeps its existing input cap. */
    val editorMaxHeight: Dp = 120.dp,
    /** Optical centering above the composer; zero retains the mobile content midpoint. */
    val emptyStateVerticalBias: Float = 0f,
    /** Content column of a settings section. */
    val settingsMaxWidth: Dp = 720.dp,
    /** Minimum height of a settings row: touch size on mobile, dense on desktop. */
    val settingsRowHeight: Dp = 56.dp,
    val dialogMinWidth: Dp = 320.dp,
    val dialogMaxWidth: Dp = 560.dp,
) {
    /** Host presets; compact desktop geometry never reduces mobile touch targets. */
    companion object {
        val Mobile = HbDimensions()
        val Desktop = HbDimensions(
            touchTarget = 32.dp,
            isDesktop = true,
            railWidth = 52.dp,
            sidebarWidth = 264.dp,
            avatarSize = 28.dp,
            headerAvatarSize = 28.dp,
            cornerRadius = 12.dp,
            headerHeight = 44.dp,
            messageMaxWidth = 760.dp,
            composerMaxWidth = 760.dp,
            navigationHeaderTopInset = 0.dp,
            sidebarHeadingHeight = 24.dp,
            messagePadding = 16.dp,
            messageCornerRadius = 12.dp,
            toolPadding = 8.dp,
            composerActionSize = 32.dp,
            composerPillHeight = 32.dp,
            navigationRowHeight = 28.dp,
            titlebarInset = 0.dp,
            editorMaxHeight = 168.dp,
            emptyStateVerticalBias = -0.14f,
            settingsRowHeight = 44.dp,
        )
        val DesktopMacOs = Desktop.copy(titlebarInset = 32.dp, navigationHeaderTopInset = 32.dp)
    }
}

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
    val tooltipDelayMillis: Int = 600,
)
