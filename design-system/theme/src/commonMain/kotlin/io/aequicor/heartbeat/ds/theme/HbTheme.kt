package io.aequicor.heartbeat.ds.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.adaptive.AdaptiveTheme
import io.aequicor.heartbeat.ds.adaptive.LocalPlatformUi
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbElevation
import io.aequicor.heartbeat.ds.tokens.HbGradients
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.ds.tokens.HbShadows
import io.aequicor.heartbeat.ds.tokens.HbShapes
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbTypography

private val LocalHbColors = staticCompositionLocalOf { HbColors.Light }
private val LocalHbGradients = staticCompositionLocalOf { HbGradients.Light }
private val LocalHbTypography = staticCompositionLocalOf { HbTypography() }
private val LocalHbSpacing = staticCompositionLocalOf { HbSpacing() }
private val LocalHbShapes = staticCompositionLocalOf { HbShapes() }
private val LocalHbElevation = staticCompositionLocalOf { HbElevation() }
private val LocalHbDimensions = staticCompositionLocalOf { defaultHbDimensions() }
private val LocalHbMotion = staticCompositionLocalOf { HbMotion() }
private val LocalHbShadows = staticCompositionLocalOf { HbShadows.Light }
private val LocalHbVisualStyle = staticCompositionLocalOf { HbVisualStyle.Glass }
private val log = Log.tag("HbTheme")

/** A soft shared visual language or the operating system's native component family. */
enum class HbVisualStyle {
    Glass,
    Neumorphic,
    Platform,
}

/** Pastel token access shared by components and layouts, independent of native UI kits. */
object HbTheme {
    val colors: HbColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHbColors.current

    val gradients: HbGradients
        @Composable
        @ReadOnlyComposable
        get() = LocalHbGradients.current

    val typography: HbTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalHbTypography.current

    val spacing: HbSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalHbSpacing.current

    val shapes: HbShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalHbShapes.current

    val elevation: HbElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalHbElevation.current

    val dimensions: HbDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalHbDimensions.current

    val motion: HbMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalHbMotion.current

    val shadows: HbShadows
        @Composable
        @ReadOnlyComposable
        get() = LocalHbShadows.current

    val visualStyle: HbVisualStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalHbVisualStyle.current
}

/**
 * Provides pastel tokens, paired shadows and an optional native kit. Preferences remain owned by the caller.
 * System light/dark and the current operating system are used by default.
 */
@Composable
fun HbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    platformUi: PlatformUi = LocalPlatformUi.current,
    visualStyle: HbVisualStyle = HbVisualStyle.Glass,
    typography: HbTypography = HbTypography(),
    spacing: HbSpacing = HbSpacing(),
    shapes: HbShapes = HbShapes(),
    elevation: HbElevation = HbElevation(),
    dimensions: HbDimensions = defaultHbDimensions(),
    motion: HbMotion = HbMotion(),
    shadows: HbShadows = if (darkTheme) HbShadows.Dark else HbShadows.Light,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) HbColors.Dark else HbColors.Light
    SideEffect(darkTheme, platformUi, visualStyle, motion.isReducedMotion) {
        log.i {
            "Theme configured: dark=$darkTheme kit=$platformUi style=$visualStyle reduced=${motion.isReducedMotion}"
        }
    }
    CompositionLocalProvider(
        LocalHbColors provides colors,
        LocalHbGradients provides if (darkTheme) HbGradients.Dark else HbGradients.Light,
        LocalHbTypography provides typography,
        LocalHbSpacing provides spacing,
        LocalHbShapes provides shapes,
        LocalHbElevation provides elevation,
        LocalHbDimensions provides dimensions,
        LocalHbMotion provides motion,
        LocalHbShadows provides shadows,
        LocalHbVisualStyle provides visualStyle,
        LocalPlatformUi provides platformUi,
    ) {
        AdaptiveTheme(
            colors = colors,
            typography = typography,
            platformUi = platformUi,
            // Foundation styles never render kit controls; loading a kit theme here would pull the
            // Java 21 macOS kit into every JDK 17 consumer on macOS.
            applyKitTheme = visualStyle == HbVisualStyle.Platform,
            content = content,
        )
    }
}
