package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import io.aequicor.heartbeat.ds.components.HbDialogOverlayInsets
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentTheme

/** Web-facing semantic tokens derived from the active host theme, not a second mini-app palette. */
internal fun HbColors.browserDocumentTheme(
    typography: HbTypography,
    spacing: HbSpacing,
    dimensions: HbDimensions,
    fontScale: Float,
    overlay: HbDialogOverlayInsets = HbDialogOverlayInsets(),
): DesktopDocumentTheme = DesktopDocumentTheme(
    isDark,
    mapOf(
        "--hb-surface" to surface.css(),
        "--hb-surface-raised" to surfaceElevated.css(),
        "--hb-text" to textPrimary.css(),
        "--hb-text-muted" to textSecondary.css(),
        "--hb-border" to outlineSubtle.css(),
        "--hb-accent" to brand.css(),
        "--hb-on-accent" to onBrand.css(),
        "--hb-accent-muted" to accentMuted.css(),
        "--hb-control" to buttonFill.css(),
        "--hb-control-hover" to interactionHoverOverlay.compositeOver(buttonFill).css(),
        "--hb-control-pressed" to pressedOverlay.compositeOver(buttonFill).css(),
        "--hb-input" to inputFill.css(),
        "--hb-focus" to focusAccent.css(),
        "--hb-danger" to error.css(),
        "--hb-on-danger" to onError.css(),
        "--hb-success" to success.css(),
        "--hb-on-success" to onSuccess.css(),
        "--hb-font-family" to "-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif",
        "--hb-font-size" to "${typography.label.fontSize.value * fontScale}px",
        "--hb-line-height" to "${typography.label.lineHeight.value * fontScale}px",
        "--hb-body-size" to "${typography.body.fontSize.value * fontScale}px",
        "--hb-body-line-height" to "${typography.body.lineHeight.value * fontScale}px",
        "--hb-title-size" to "${typography.title.fontSize.value * fontScale}px",
        "--hb-caption-size" to "${typography.caption.fontSize.value * fontScale}px",
        "--hb-space-xs" to "${spacing.xs.value}px",
        "--hb-space-s" to "${spacing.s.value}px",
        "--hb-space-m" to "${spacing.m.value}px",
        "--hb-space-l" to "${spacing.l.value}px",
        "--hb-space-xl" to "${spacing.xl.value}px",
        "--hb-overlay-width" to "${overlay.width.value}px",
        "--hb-overlay-height" to "${overlay.height.value}px",
        "--hb-control-height" to "${dimensions.controlHeight.value}px",
        "--hb-control-radius" to "${dimensions.controlCornerRadius.value}px",
        "--hb-field-radius" to "${dimensions.fieldCornerRadius.value}px",
        "--hb-focus-width" to "${dimensions.fieldFocusWidth.value}px",
        "--hb-focus-offset" to "${dimensions.focusOutset.value}px",
    ),
)

/** Opaque host tokens are serialized as CSS rgb, preserving channels without a hardcoded palette. */
private fun Color.css(): String {
    val argb = toArgb()
    return "rgb(${(argb ushr RED_SHIFT) and CHANNEL},${(argb ushr GREEN_SHIFT) and CHANNEL},${argb and CHANNEL})"
}

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 255
