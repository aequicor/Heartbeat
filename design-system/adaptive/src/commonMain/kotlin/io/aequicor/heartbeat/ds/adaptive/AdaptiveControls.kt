package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbTypography

internal val LocalAdaptiveColors = staticCompositionLocalOf { HbColors.Light }

/**
 * Maps semantic tokens into the selected native kit without exposing kit types to consumers.
 * [applyKitTheme] loads the kit only when native controls are rendered: the macOS kit ships Java 21
 * classes, so Foundation-only styles must never touch it on JDK 17 runtimes. Content is movable, so
 * switching kits or styles keeps remembered state below the theme.
 */
@Composable
fun AdaptiveTheme(
    colors: HbColors,
    typography: HbTypography,
    platformUi: PlatformUi,
    applyKitTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }
    val selectionColors = remember(colors) {
        TextSelectionColors(handleColor = colors.focusAccent, backgroundColor = colors.selectionHighlight)
    }
    // Provided inside the kit theme so native schemes cannot replace the palette selection.
    val themedContent = @Composable {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) { movableContent() }
    }
    CompositionLocalProvider(LocalAdaptiveColors provides colors, LocalPlatformUi provides platformUi) {
        if (applyKitTheme) platformKit(platformUi).Theme(colors, typography, themedContent) else themedContent()
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
    dimensions: HbDimensions = HbDimensions(),
) {
    platformKit(LocalPlatformUi.current).Button(text, onClick, modifier, enabled, primary, dimensions)
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
    fun Button(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        primary: Boolean,
        dimensions: HbDimensions,
    )

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
