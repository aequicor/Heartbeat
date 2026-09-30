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
import io.aequicor.heartbeat.ds.tokens.HbShapes
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbSurfaceColors
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.ds.tokens.HbWelcome

private val LocalHbColors = staticCompositionLocalOf { HbColors.Light }
private val LocalHbGradients = staticCompositionLocalOf { HbGradients.Light }
private val LocalHbTypography = staticCompositionLocalOf { HbTypography() }
private val LocalHbSpacing = staticCompositionLocalOf { HbSpacing() }
private val LocalHbShapes = staticCompositionLocalOf { HbShapes() }
private val LocalHbElevation = staticCompositionLocalOf { HbElevation() }
private val LocalHbDimensions = staticCompositionLocalOf { defaultHbDimensions() }
private val LocalHbSurfaces = staticCompositionLocalOf { HbSurfaceColors.Light }
private val LocalHbMotion = staticCompositionLocalOf { HbMotion() }
private val LocalHbVisualStyle = staticCompositionLocalOf { HbVisualStyle.Flat }
private val log = Log.tag("HbTheme")
private val fontLog = Log.tag("HbHostFont")

/**
 * [Flat] is the one visual language of the application: the dense studio style drawn with Compose Foundation —
 * opaque surfaces, quiet hover/pressed/selected fills without outlines, a keyboard-only focus ring and no glass,
 * blur or neumorphic shadows. [Platform] renders controls with the native kit of the host for previews.
 */
enum class HbVisualStyle {
    Flat,
    Platform,
}

/**
 * Token access shared by components and layouts, independent of native UI kits. Every value belongs to one set
 * of tokens whose host presets (touch or dense desktop) the [HbTheme] function selects.
 */
object HbTheme {
    val welcome: HbWelcome = HbWelcome()

    /** Opaque window surfaces (sidebar, headers, conversation) matching [colors]. */
    val surfaces: HbSurfaceColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHbSurfaces.current

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

    val visualStyle: HbVisualStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalHbVisualStyle.current
}

/**
 * Scopes the host palette's dark oak attention variant to a pending questionnaire rendered by the flat
 * controls. Typography, geometry, motion preferences, window surfaces and the platform kit remain inherited
 * from the surrounding [HbTheme].
 */
@Composable
fun HbQuestionnaireTheme(content: @Composable () -> Unit) {
    val hostColors = HbTheme.colors
    val questionnaireColors = remember(hostColors) { hostColors.forQuestionnaire() }
    CompositionLocalProvider(LocalHbColors provides questionnaireColors, content = content)
}

/** Host typography preset with the native interface font; the font resolution is logged once. */
@Composable
fun rememberHostTypography(isDesktop: Boolean): HbTypography {
    val font = remember { hostFontResolution() }
    SideEffect(font) {
        if (font.isFallback) {
            fontLog.w {
                "Host font fallback: requested=${font.requestedFamily.orEmpty()} actual=${font.actualFamily.orEmpty()}"
            }
        } else if (font.actualFamily != null) {
            fontLog.i { "Host system font: requested=${font.requestedFamily.orEmpty()} actual=${font.actualFamily}" }
        }
    }
    return remember(font.family, isDesktop) {
        (if (isDesktop) HbTypography.Desktop else HbTypography.Mobile).withFamily(font.family)
    }
}

/**
 * Provides the single token set of the flat studio style and an optional native kit. [dimensions] chooses the host
 * preset (touch or dense desktop); colors, surfaces, typography and shapes follow it. Preferences remain owned by
 * the caller. System light/dark and the current operating system are used by default.
 */
@Composable
fun HbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    platformUi: PlatformUi = LocalPlatformUi.current,
    visualStyle: HbVisualStyle = HbVisualStyle.Flat,
    dimensions: HbDimensions = defaultHbDimensions(),
    typography: HbTypography = rememberHostTypography(dimensions.isDesktop),
    spacing: HbSpacing = HbSpacing(),
    shapes: HbShapes = if (dimensions.isDesktop) HbShapes.Desktop else HbShapes.Mobile,
    elevation: HbElevation = HbElevation(),
    motion: HbMotion = HbMotion(),
    content: @Composable () -> Unit,
) {
    val colors = HbColors.forHost(darkTheme, dimensions.isDesktop)
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
        LocalHbSurfaces provides HbSurfaceColors.forHost(darkTheme, dimensions.isDesktop),
        LocalHbMotion provides motion,
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
