package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbTypography

internal val LocalAdaptiveColors = staticCompositionLocalOf { HbColors.Light }

/** Maps semantic tokens into the selected native kit without exposing kit types to consumers. */
@Composable
@NonRestartableComposable
fun AdaptiveTheme(
    colors: HbColors,
    typography: HbTypography,
    platformUi: PlatformUi,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAdaptiveColors provides colors, LocalPlatformUi provides platformUi) {
        platformKit(platformUi).Theme(colors, typography, content)
    }
}

/** Stateless button adapter shared by public design-system components. */
@Composable
@NonRestartableComposable
fun AdaptiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    platformKit(LocalPlatformUi.current).Button(text, onClick, modifier, enabled, primary)
}

/** Stateless text input, including multiline chat composition, in the selected native kit. */
@Composable
@NonRestartableComposable
fun AdaptiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    platformKit(LocalPlatformUi.current).TextField(value, onValueChange, modifier, placeholder, enabled, singleLine)
}

/** Platform boundary for UI primitives; implementations are stateless singleton strategies. */
internal interface PlatformKit {
    @Composable
    fun Theme(colors: HbColors, typography: HbTypography, content: @Composable () -> Unit)

    @Composable
    fun Button(text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, primary: Boolean)

    @Composable
    fun TextField(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier,
        placeholder: String,
        enabled: Boolean,
        singleLine: Boolean,
    )
}
