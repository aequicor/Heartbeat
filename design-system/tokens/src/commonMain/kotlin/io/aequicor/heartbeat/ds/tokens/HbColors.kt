package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * The single palette of the application: quiet lavender accents on cool neutral surfaces.
 * [Light] and [Dark] serve touch hosts; [DesktopLight] and [DesktopDark] are the neutral, opaque desktop presets
 * of the studio that leave the accent to selection and deliberate actions. The theme picks one by host.
 */
@Immutable
data class HbColors(
    val brand: Color = Color(0xFF7560D5),
    val primary: Color = Color(0xFFE2DBFF),
    val secondary: Color = Color(0xFFF2C1AB),
    val background: Color = Color(0xFFEAF3FA),
    val surface: Color = Color(0xFFF5F8FC),
    val error: Color = Color(0xFFE6B1BE),
    val success: Color = Color(0xFFB4D7C5),
    val warning: Color = Color(0xFFE9D6A6),
    val textPrimary: Color = Color(0xFF18203A),
    val dataViolet: Color = Color(0xFFD9D1F6),
    val dataCyan: Color = Color(0xFFBDDFEA),
    val isDark: Boolean = false,
) {
    val textSecondary: Color = textPrimary.copy(alpha = 0.72f).compositeOver(surface)
    val syntaxKeyword: Color = if (isDark) Color(0xFFDDD2F4) else Color(0xFF654D92)
    val syntaxString: Color = if (isDark) Color(0xFFB4D8C2) else Color(0xFF356953)
    val syntaxNumber: Color = if (isDark) Color(0xFFE5C8A6) else Color(0xFF85522F)
    val syntaxComment: Color = if (isDark) Color(0xFFC6CEDC) else Color(0xFF546075)
    val syntaxType: Color = if (isDark) Color(0xFFB8D2E2) else Color(0xFF315F82)
    val syntaxFunction: Color = if (isDark) Color(0xFFE2C6DD) else Color(0xFF795076)
    val syntaxAnnotation: Color = if (isDark) Color(0xFFD2C293) else Color(0xFF6B552F)
    val consoleSurface: Color = if (isDark) Color(0xFF17191F) else Color(0xFF303642)
    val consoleText: Color = Color(0xFFE4E8F0)
    val consoleMuted: Color = Color(0xFFA8B2C3)
    val consoleCommand: Color = Color(0xFFCBB8E8)
    val consoleInfo: Color = Color(0xFFA6C8DA)
    val consoleSuccess: Color = Color(0xFFABD1B7)
    val consoleWarning: Color = Color(0xFFE1C894)
    val consoleError: Color = Color(0xFFE0A8B2)
    val onPrimary: Color = accessibleContentColor(primary)
    val onBrand: Color = accessibleContentColor(brand)
    val onSecondary: Color = accessibleContentColor(secondary)
    val onError: Color = accessibleContentColor(error)
    val onSuccess: Color = accessibleContentColor(success)
    val onWarning: Color = accessibleContentColor(warning)
    val onSurface: Color = textPrimary
    val outline: Color = textPrimary.copy(alpha = 0.60f).compositeOver(surface)
    val outlineSubtle: Color = textPrimary.copy(alpha = 0.12f).compositeOver(surface)
    val surfaceElevated: Color = textPrimary.copy(alpha = 0.035f).compositeOver(surface)
    val assistantSurface: Color = if (isDark) surfaceElevated else Color.White
    val primaryContainer: Color = brand.copy(alpha = 0.12f).compositeOver(surface)
    val secondaryContainer: Color = secondary.copy(alpha = 0.12f).compositeOver(surface)
    val errorContainer: Color = error.copy(alpha = 0.12f).compositeOver(surface)
    val successContainer: Color = success.copy(alpha = 0.12f).compositeOver(surface)
    val warningContainer: Color = warning.copy(alpha = 0.12f).compositeOver(surface)
    val infoContainer: Color = dataCyan.copy(alpha = 0.12f).compositeOver(surface)

    /** Quiet fill of secondary buttons: a tint of the text color, never an outline. */
    val buttonFill: Color = textPrimary.copy(alpha = 0.06f).compositeOver(surface)

    /** Flat input fill; fields have no border or inner shadow, focus is shown by the ring. */
    val inputFill: Color = textPrimary.copy(alpha = 0.04f).compositeOver(surface)

    /** Neutral selection remains distinct from hover without adding an outline. */
    val selectedContainer: Color = onSurface.copy(alpha = 0.10f).compositeOver(surface)

    /** Saturated semantic accent keeps keyboard focus legible against light and dark surfaces. */
    val focusAccent: Color = if (isDark) Color(0xFFC2B4EE) else Color(0xFF6947A3)
    val focusRing: Color = focusAccent.copy(alpha = 0.80f)
    val focusOuter: Color = Color.Black
    val focusInner: Color = Color.White
    val accentMuted: Color = brand.copy(alpha = 0.22f).compositeOver(surface)
    val onAccentMuted: Color = textPrimary
    val hoverOverlay: Color = Color.White.copy(alpha = 0.10f)
    val interactionHoverOverlay: Color = onSurface.copy(alpha = 0.05f)
    val pressedOverlay: Color = onSurface.copy(alpha = 0.09f)

    /** Dims content behind a modal drawer or sheet. */
    val scrim: Color = Color.Black.copy(alpha = if (isDark) 0.56f else 0.32f)

    /**
     * Soft drop shadow of floating popups (menus, tooltips, dialogs) — the only elevated surfaces of the flat
     * style: they overlap arbitrary content, so a hairline alone would not separate them.
     */
    val popupShadow: Color = Color.Black.copy(alpha = if (isDark) 0.40f else 0.12f)

    /** Host presets: touch ([Light], [Dark]) and dense desktop ([DesktopLight], [DesktopDark]). */
    companion object {
        val Light = HbColors()
        val Dark = HbColors(
            brand = Color(0xFFC0AFFC),
            primary = Color(0xFFD2C5FF),
            secondary = Color(0xFFD9B4A6),
            background = Color(0xFF1C2432),
            surface = Color(0xFF273243),
            error = Color(0xFFD79BAE),
            success = Color(0xFFA4CBB9),
            warning = Color(0xFFDAC78F),
            textPrimary = Color(0xFFF1F3FC),
            dataViolet = Color(0xFFB9A9E9),
            dataCyan = Color(0xFF9CBCCA),
            isDark = true,
        )
        val DesktopLight = Light.copy(
            brand = Color(0xFF7B6D9A),
            primary = Color(0xFFE9E5EF),
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFFFFFFF),
            textPrimary = Color(0xFF242426),
            dataViolet = Color(0xFFBDB4CE),
            dataCyan = Color(0xFFC0CDD1),
        )
        val DesktopDark = Dark.copy(
            brand = Color(0xFFBAAFD0),
            primary = Color(0xFFBFB3D4),
            background = Color(0xFF202022),
            surface = Color(0xFF242426),
            textPrimary = Color(0xFFEEEEF0),
            dataViolet = Color(0xFFBDB4CE),
            dataCyan = Color(0xFFC0CDD1),
        )

        /** Preset of a host: dense neutral surfaces on desktop, touch surfaces elsewhere. */
        fun forHost(isDark: Boolean, isDesktop: Boolean): HbColors = when {
            isDesktop && isDark -> DesktopDark
            isDesktop -> DesktopLight
            isDark -> Dark
            else -> Light
        }
    }
}

/** WCAG relative-luminance contrast for two opaque colors. */
fun contrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}

/** Chooses the black or white text color with the strongest contrast on an opaque background. */
fun accessibleContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
        Color.Black
    } else {
        Color.White
    }
