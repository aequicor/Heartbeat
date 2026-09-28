package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.nucleusframework.macoscompose.components.PushButton
import dev.nucleusframework.macoscompose.components.PushButtonStyle
import dev.nucleusframework.macoscompose.components.Text
import dev.nucleusframework.macoscompose.components.TextArea
import dev.nucleusframework.macoscompose.components.TextField
import dev.nucleusframework.macoscompose.theme.MacosDuration
import dev.nucleusframework.macoscompose.theme.MacosTheme
import dev.nucleusframework.macoscompose.theme.Typography
import dev.nucleusframework.macoscompose.theme.darkColorScheme
import dev.nucleusframework.macoscompose.theme.defaultComponentStyling
import dev.nucleusframework.macoscompose.theme.lightColorScheme
import dev.nucleusframework.macoscompose.theme.macosTween
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.ds.tokens.accessibleContentColor

internal object MacOsKit : PlatformKit {
    @Composable
    override fun Theme(colors: HbColors, typography: HbTypography, content: @Composable () -> Unit) {
        val scheme = remember(colors) {
            val base = if (colors.isDark) darkColorScheme() else lightColorScheme()
            base.copy(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                accent = colors.brand,
                onAccent = colors.onBrand,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                textPrimary = colors.textPrimary,
                textSecondary = colors.textSecondary,
                textTertiary = colors.textSecondary,
                outline = colors.outline,
                outlineVariant = colors.outlineSubtle,
                card = colors.surface,
                cardForeground = colors.textPrimary,
                inputBackground = colors.surface,
                inputFocusBackground = colors.surfaceElevated,
                inputFocusBorder = colors.focusAccent,
            )
        }
        val componentStyling = remember(scheme, colors) {
            val native = defaultComponentStyling(scheme)
            native.copy(
                textField = native.textField.copy(
                    colors = native.textField.colors.copy(
                        background = colors.surface,
                        overGlassBackground = colors.surface,
                        overGlassFocusedBackground = colors.surfaceElevated,
                        text = colors.textPrimary,
                        placeholder = colors.textSecondary,
                        cursor = colors.textPrimary,
                        border = colors.outlineSubtle,
                    ),
                ),
            )
        }
        MacosTheme(
            darkTheme = colors.isDark,
            colorScheme = scheme,
            componentStyling = componentStyling,
            typography = Typography(
                largeTitle = typography.display,
                title1 = typography.title,
                title2 = typography.title,
                title3 = typography.title,
                headline = typography.label,
                body = typography.body,
                callout = typography.body,
                subheadline = typography.label,
                footnote = typography.caption,
                caption1 = typography.label,
                caption2 = typography.caption,
            ),
            content = content,
        )
    }

    @Composable
    override fun Button(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        primary: Boolean,
        dimensions: HbDimensions,
    ) {
        val colors = LocalAdaptiveColors.current
        val interaction = remember { MutableInteractionSource() }
        val isFocused by interaction.collectIsFocusedAsState()
        val focusModifier = modifier.adaptiveFocusOutline(
            isFocused,
            RoundedCornerShape(dimensions.controlCornerRadius),
            colors,
            dimensions,
        )
        val isHovered by interaction.collectIsHoveredAsState()
        val isPressed by interaction.collectIsPressedAsState()
        // The kit hardcodes white labels; track its overlay animation to preserve AA throughout transitions.
        val overlay by animateColorAsState(
            targetValue = when {
                isPressed && enabled -> colors.pressedOverlay
                isHovered && enabled -> colors.hoverOverlay
                else -> Color.Transparent
            },
            animationSpec = macosTween(MacosDuration.Fast),
        )
        val foreground = if (primary) macOsAccentContentColor(colors, overlay) else colors.textPrimary
        PushButton(
            onClick = onClick,
            modifier = focusModifier,
            enabled = enabled,
            style = if (primary) PushButtonStyle.Default else PushButtonStyle.Neutral,
            interactionSource = interaction,
        ) {
            Text(text, color = foreground)
        }
    }

    @Composable
    override fun TextField(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier,
        placeholder: String,
        enabled: Boolean,
        singleLine: Boolean,
    ) {
        val colors = LocalAdaptiveColors.current
        if (singleLine) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier,
                placeholder = { Text(placeholder, color = colors.textSecondary) },
                enabled = enabled,
                singleLine = true,
            )
        } else {
            TextArea(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier,
                placeholder = { Text(placeholder, color = colors.textSecondary) },
                enabled = enabled,
            )
        }
    }
}

internal fun macOsAccentContentColor(colors: HbColors, overlay: Color): Color =
    accessibleContentColor(overlay.compositeOver(colors.brand))
