package io.aequicor.heartbeat.ds.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
import io.aequicor.heartbeat.ds.tokens.HbStudioColors
import io.aequicor.heartbeat.ds.tokens.HbStudioDimensions
import io.aequicor.heartbeat.ds.tokens.HbStudioStyle
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.ds.tokens.HbWelcome

private val LocalHbColors = staticCompositionLocalOf { HbColors.Light }
private val LocalHbGradients = staticCompositionLocalOf { HbGradients.Light }
private val LocalHbTypography = staticCompositionLocalOf { HbTypography() }
private val LocalHbSpacing = staticCompositionLocalOf { HbSpacing() }
private val LocalHbShapes = staticCompositionLocalOf { HbShapes() }
private val LocalHbElevation = staticCompositionLocalOf { HbElevation() }
private val LocalHbDimensions = staticCompositionLocalOf { defaultHbDimensions() }
private val LocalHbStudioDimensions = staticCompositionLocalOf { defaultHbStudioDimensions() }
private val LocalHbMotion = staticCompositionLocalOf { HbMotion() }
private val LocalHbShadows = staticCompositionLocalOf { HbShadows.Light }
private val LocalHbVisualStyle = staticCompositionLocalOf { HbVisualStyle.Glass }
private val log = Log.tag("HbTheme")
private val fontLog = Log.tag("HbStudioFont")

/** A soft shared visual language or the operating system's native component family. */
enum class HbVisualStyle {
    Glass,
    Neumorphic,
    Platform,
}

/** Pastel token access shared by components and layouts, independent of native UI kits. */
object HbTheme {
    val welcome: HbWelcome = HbWelcome()
    val studioDimensions: HbStudioDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalHbStudioDimensions.current

    val studioColors: HbStudioColors
        @Composable
        @ReadOnlyComposable
        get() = when {
            studioDimensions.isDesktop && colors.isDark -> HbStudioColors.DesktopDark
            studioDimensions.isDesktop -> HbStudioColors.DesktopLight
            colors.isDark -> HbStudioColors.Dark
            else -> HbStudioColors.Light
        }

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

/** Applies the studio's pearl palette, system typography and rounded surfaces, retaining theme preferences. */
@Composable
fun HbStudioTheme(content: @Composable () -> Unit) {
    val isDesktop = HbTheme.studioDimensions.isDesktop
    val colors = studioColors(HbTheme.colors.isDark, isDesktop)
    val font = remember { studioFontResolution() }
    val typography = remember(font.family, isDesktop) { HbStudioStyle.typography(font.family, isDesktop) }
    SideEffect(font) {
        if (font.isFallback) {
            fontLog.w {
                "Studio font fallback: requested=${font.requestedFamily.orEmpty()} " +
                    "actual=${font.actualFamily.orEmpty()}"
            }
        } else if (font.actualFamily != null) {
            fontLog.i {
                "Studio system font: requested=${font.requestedFamily.orEmpty()} " +
                    "actual=${font.actualFamily}"
            }
        }
    }
    CompositionLocalProvider(
        LocalHbColors provides colors,
        LocalHbTypography provides typography,
        LocalHbShapes provides if (isDesktop) HbStudioStyle.desktopShapes else HbStudioStyle.shapes,
        content = content,
    )
}

private fun studioColors(isDark: Boolean, isDesktop: Boolean): HbColors = when {
    isDesktop && isDark -> HbStudioStyle.desktopDarkColors
    isDesktop -> HbStudioStyle.desktopLightColors
    isDark -> HbStudioStyle.darkColors
    else -> HbStudioStyle.lightColors
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
    studioDimensions: HbStudioDimensions = defaultHbStudioDimensions(),
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
        LocalHbStudioDimensions provides studioDimensions,
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
