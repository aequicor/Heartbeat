package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** Quiet pastel palette with readable semantic roles and translucent glass treatments. */
@Immutable
data class HbColors(
    val brand: Color = Color(0xFFB8A4EA),
    val primary: Color = Color(0xFFC0B3EF),
    val secondary: Color = Color(0xFFF2C1AB),
    val background: Color = Color(0xFFE9EDF3),
    val surface: Color = Color(0xFFE9EDF3),
    val error: Color = Color(0xFFE6B1BE),
    val success: Color = Color(0xFFB4D7C5),
    val warning: Color = Color(0xFFE9D6A6),
    val textPrimary: Color = Color(0xFF293247),
    val dataViolet: Color = Color(0xFFCCBCEB),
    val dataCyan: Color = Color(0xFFAED3DD),
    val shadowLight: Color = Color(0xFFFFFFFF),
    val shadowDark: Color = Color(0xFFBAC5D6),
    val isDark: Boolean = false,
) {
    val textSecondary: Color = textPrimary.copy(alpha = 0.72f).compositeOver(surface)
    val syntaxKeyword: Color = if (isDark) Color(0xFFC5B6DF) else Color(0xFF654D92)
    val syntaxString: Color = if (isDark) Color(0xFFA1C7B0) else Color(0xFF356953)
    val syntaxNumber: Color = if (isDark) Color(0xFFD9BA95) else Color(0xFF85522F)
    val syntaxComment: Color = if (isDark) Color(0xFFB3BDCD) else Color(0xFF546075)
    val syntaxType: Color = if (isDark) Color(0xFFA5C2D5) else Color(0xFF315F82)
    val syntaxFunction: Color = if (isDark) Color(0xFFD3B4CE) else Color(0xFF795076)
    val syntaxAnnotation: Color = if (isDark) Color(0xFFD2C293) else Color(0xFF745D36)
    val consoleSurface: Color = if (isDark) Color(0xFF202631) else Color(0xFF303642)
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
    val buttonFill: Color = surface
    val inputFill: Color = surface
    val accentMuted: Color = brand.copy(alpha = 0.22f).compositeOver(surface)
    val onAccentMuted: Color = textPrimary
    val hoverOverlay: Color = Color.White.copy(alpha = 0.10f)
    val interactionHoverOverlay: Color = brand.copy(alpha = 0.16f)
    val pressedOverlay: Color = Color.Black.copy(alpha = 0.08f)

    /** Dims content behind a modal drawer or sheet. */
    val scrim: Color = shadowDark.copy(alpha = if (isDark) 0.72f else 0.56f)
    val glassHighlight: Color = Color.White.copy(alpha = if (isDark) 0.16f else 0.84f)
    val glassBorder: Color = textPrimary.copy(alpha = if (isDark) 0.14f else 0.08f)
    val glassShadow: Color = shadowDark.copy(alpha = if (isDark) 0.28f else 0.18f)
    val glassTint: Color = surface.copy(alpha = if (isDark) 0.84f else 0.86f)
    val glassBackdropLavender: Color = brand.copy(alpha = 0.08f)
    val glassBackdropBlue: Color = dataCyan.copy(alpha = if (isDark) 0.06f else 0.10f)
    val glassBackdropPeach: Color = secondary.copy(alpha = if (isDark) 0.04f else 0.07f)

    /** Matching light and dark pastel surfaces with complementary highlights and shadows. */
    companion object {
        val Light = HbColors()
        val Dark = HbColors(
            brand = Color(0xFFC2B4EE),
            primary = Color(0xFFBDB0EC),
            secondary = Color(0xFFD9B4A6),
            background = Color(0xFF272B36),
            surface = Color(0xFF272B36),
            error = Color(0xFFD79BAE),
            success = Color(0xFFA4CBB9),
            warning = Color(0xFFDAC78F),
            textPrimary = Color(0xFFF1F0F7),
            dataViolet = Color(0xFFC4AFDF),
            dataCyan = Color(0xFFA3C5D1),
            shadowLight = Color(0xFF393F4F),
            shadowDark = Color(0xFF181B23),
            isDark = true,
        )
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
