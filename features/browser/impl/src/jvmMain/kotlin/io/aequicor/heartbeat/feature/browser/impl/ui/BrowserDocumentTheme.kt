package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentTheme

/** Web-facing semantic tokens derived from the active host theme, not a second mini-app palette. */
internal fun browserDocumentTheme(
    colors: HbColors,
    typography: HbTypography,
    spacing: HbSpacing,
    dimensions: HbDimensions,
    fontScale: Float,
): DesktopDocumentTheme = DesktopDocumentTheme(
    colors.isDark,
    mapOf(
        "--hb-surface" to colors.surface.css(),
        "--hb-surface-raised" to colors.surfaceElevated.css(),
        "--hb-text" to colors.textPrimary.css(),
        "--hb-text-muted" to colors.textSecondary.css(),
        "--hb-border" to colors.outlineSubtle.css(),
        "--hb-accent" to colors.brand.css(),
        "--hb-on-accent" to colors.onBrand.css(),
        "--hb-accent-muted" to colors.accentMuted.css(),
        "--hb-control" to colors.buttonFill.css(),
        "--hb-input" to colors.inputFill.css(),
        "--hb-focus" to colors.focusAccent.css(),
        "--hb-danger" to colors.error.css(),
        "--hb-on-danger" to colors.onError.css(),
        "--hb-success" to colors.success.css(),
        "--hb-on-success" to colors.onSuccess.css(),
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
        "--hb-control-height" to "${dimensions.controlHeight.value}px",
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
