package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import io.aequicor.heartbeat.ds.tokens.HbColors
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
    override fun Button(text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, primary: Boolean) {
        val colors = LocalAdaptiveColors.current
        val base = fluentButtonColor(colors, primary)
        val hovered = if (primary) base else base.copy(fillColor = colors.surfaceElevated)
        val buttonColors = ButtonDefaults.buttonColors(
            default = base,
            hovered = hovered,
            pressed = hovered.copy(borderBrush = SolidColor(colors.outline)),
        )
        if (primary) {
            AccentButton(onClick = onClick, modifier = modifier, disabled = !enabled, buttonColors = buttonColors) {
                Text(text)
            }
        } else {
            Button(onClick = onClick, modifier = modifier, disabled = !enabled, buttonColors = buttonColors) {
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
                focused = base.copy(bottomLineFillColor = colors.brand),
                pressed = base,
            ),
        )
    }
}

internal fun fluentButtonColor(colors: HbColors, primary: Boolean): ButtonColor = ButtonColor(
    fillColor = if (primary) colors.brand else colors.surface,
    contentColor = if (primary) colors.onBrand else colors.textPrimary,
    borderBrush = SolidColor(if (primary) colors.brand else colors.outline),
)

internal fun fluentTextFieldColor(colors: HbColors): TextFieldColor = TextFieldColor(
    fillColor = colors.surface,
    contentColor = colors.textPrimary,
    placeholderColor = colors.textSecondary,
    bottomLineFillColor = colors.outline,
    borderBrush = SolidColor(colors.outline),
    cursorBrush = SolidColor(colors.textPrimary),
)
