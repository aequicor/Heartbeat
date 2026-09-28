package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.github.composefluent.FluentTheme
import io.github.composefluent.Typography
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ButtonColor
import io.github.composefluent.component.ButtonDefaults
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.component.TextFieldColor
import io.github.composefluent.component.TextFieldDefaults
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors

internal object FluentKit : PlatformKit {
    @Composable
    override fun Theme(colors: HbColors, typography: HbTypography, content: @Composable () -> Unit) {
        val fluentColors = remember(colors) {
            if (colors.isDark) darkColors(colors.brand) else lightColors(colors.brand)
        }
        FluentTheme(
            colors = fluentColors,
            typography = Typography(
                caption = typography.caption,
                body = typography.body,
                bodyStrong = typography.label,
                bodyLarge = typography.body,
                subtitle = typography.title,
                title = typography.title,
                titleLarge = typography.display,
                display = typography.display,
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
        val interaction = remember { MutableInteractionSource() }
        val isFocused by interaction.collectIsFocusedAsState()
        val colors = LocalAdaptiveColors.current
        val focusModifier = modifier.adaptiveFocusOutline(
            isFocused,
            RoundedCornerShape(dimensions.controlCornerRadius),
            colors,
            dimensions,
        )
        val base = fluentButtonColor(colors, primary)
        val hovered = base.copy(fillColor = colors.interactionHoverOverlay.compositeOver(base.fillColor))
        val buttonColors = ButtonDefaults.buttonColors(
            default = base,
            hovered = hovered,
            pressed = base.copy(fillColor = colors.pressedOverlay.compositeOver(base.fillColor)),
        )
        if (primary) {
            AccentButton(
                onClick = onClick,
                modifier = focusModifier,
                disabled = !enabled,
                buttonColors = buttonColors,
                interaction = interaction,
            ) {
                Text(text)
            }
        } else {
            Button(
                onClick = onClick,
                modifier = focusModifier,
                disabled = !enabled,
                buttonColors = buttonColors,
                interaction = interaction,
            ) {
                Text(text)
            }
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
        val base = fluentTextFieldColor(colors)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            placeholder = { Text(placeholder) },
            enabled = enabled,
            singleLine = singleLine,
            isClearable = false,
            colors = TextFieldDefaults.defaultTextFieldColors(
                default = base,
                hovered = base.copy(fillColor = colors.surfaceElevated),
                focused = base.copy(bottomLineFillColor = colors.focusAccent),
                pressed = base,
            ),
        )
    }
}

internal fun fluentButtonColor(colors: HbColors, primary: Boolean): ButtonColor = ButtonColor(
    fillColor = if (primary) colors.brand else colors.surface,
    contentColor = if (primary) colors.onBrand else colors.textPrimary,
    borderBrush = SolidColor(if (primary) colors.brand else colors.outlineSubtle),
)

internal fun fluentTextFieldColor(colors: HbColors): TextFieldColor = TextFieldColor(
    fillColor = colors.surface,
    contentColor = colors.textPrimary,
    placeholderColor = colors.textSecondary,
    bottomLineFillColor = colors.outline,
    borderBrush = SolidColor(colors.outline),
    cursorBrush = SolidColor(colors.textPrimary),
)
