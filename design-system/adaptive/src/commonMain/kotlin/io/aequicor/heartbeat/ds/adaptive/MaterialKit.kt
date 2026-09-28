package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbTypography

internal object MaterialKit : PlatformKit {
    @Composable
    override fun Theme(colors: HbColors, typography: HbTypography, content: @Composable () -> Unit) {
        val base = if (colors.isDark) darkColorScheme() else lightColorScheme()
        MaterialTheme(
            colorScheme = base.copy(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                primaryContainer = colors.primaryContainer,
                onPrimaryContainer = colors.textPrimary,
                secondary = colors.secondary,
                onSecondary = colors.onSecondary,
                secondaryContainer = colors.secondaryContainer,
                onSecondaryContainer = colors.textPrimary,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.surfaceElevated,
                onSurfaceVariant = colors.textSecondary,
                surfaceContainer = colors.surface,
                surfaceContainerHigh = colors.surfaceElevated,
                surfaceContainerHighest = colors.surfaceElevated,
                error = colors.error,
                onError = colors.onError,
                errorContainer = colors.errorContainer,
                onErrorContainer = colors.textPrimary,
                outline = colors.outline,
                outlineVariant = colors.outlineSubtle,
            ),
            typography = Typography(
                displayLarge = typography.display,
                titleLarge = typography.title,
                bodyLarge = typography.body,
                bodyMedium = typography.body,
                labelLarge = typography.label,
                bodySmall = typography.caption,
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
        val focusModifier = modifier.adaptiveFocusOutline(
            isFocused,
            RoundedCornerShape(dimensions.controlCornerRadius),
            LocalAdaptiveColors.current,
            dimensions,
        )
        val controls = materialControlColors(LocalAdaptiveColors.current)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides dimensions.touchTarget) {
            if (primary) {
                Button(
                    onClick = onClick,
                    modifier = focusModifier,
                    enabled = enabled,
                    interactionSource = interaction,
                    shape = RoundedCornerShape(dimensions.controlCornerRadius),
                    contentPadding = PaddingValues(horizontal = HbSpacing().l, vertical = HbSpacing().xs),
                ) { Text(text) }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    modifier = focusModifier,
                    enabled = enabled,
                    interactionSource = interaction,
                    shape = RoundedCornerShape(dimensions.controlCornerRadius),
                    contentPadding = PaddingValues(horizontal = HbSpacing().l, vertical = HbSpacing().xs),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = controls.content),
                ) {
                    Text(text)
                }
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
        val controls = materialControlColors(LocalAdaptiveColors.current)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            singleLine = singleLine,
            placeholder = { Text(placeholder) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = controls.focusedBorder,
                cursorColor = controls.cursor,
            ),
        )
    }
}

internal data class MaterialControlColors(val content: Color, val focusedBorder: Color, val cursor: Color)

internal fun materialControlColors(colors: HbColors): MaterialControlColors = MaterialControlColors(
    content = colors.textPrimary,
    focusedBorder = colors.focusAccent,
    cursor = colors.textPrimary,
)
