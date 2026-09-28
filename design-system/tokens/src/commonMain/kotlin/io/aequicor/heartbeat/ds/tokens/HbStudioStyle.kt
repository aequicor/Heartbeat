package io.aequicor.heartbeat.ds.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pearl glass, system-font reading typography and rounded surfaces scoped to the conversation studio. */
object HbStudioStyle {
    private val baseTypography = HbTypography()

    /** Cool white surfaces and ink text matching the studio, without changing the application-wide palette. */
    val lightColors: HbColors = HbColors.Light.copy(
        brand = Color(0xFF7560D5),
        primary = Color(0xFFE2DBFF),
        background = Color(0xFFEAF3FA),
        surface = Color(0xFFF5F8FC),
        textPrimary = Color(0xFF18203A),
        dataViolet = Color(0xFFD9D1F6),
        dataCyan = Color(0xFFBDDFEA),
        shadowDark = Color(0xFFB7C8DB),
    )

    /** A graphite counterpart with the same surface hierarchy and readable lavender accents. */
    val darkColors: HbColors = HbColors.Dark.copy(
        brand = Color(0xFFC0AFFC),
        primary = Color(0xFFD2C5FF),
        background = Color(0xFF1C2432),
        surface = Color(0xFF273243),
        textPrimary = Color(0xFFF1F3FC),
        dataViolet = Color(0xFFB9A9E9),
        dataCyan = Color(0xFF9CBCCA),
        shadowDark = Color(0xFF111924),
    )

    /** Neutral desktop chrome leaves the lilac accent to selection and deliberate actions. */
    val desktopLightColors: HbColors = HbColors.Light.copy(
        brand = Color(0xFF7B6D9A),
        primary = Color(0xFFE9E5EF),
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF242426),
        dataViolet = Color(0xFFBDB4CE),
        dataCyan = Color(0xFFC0CDD1),
        shadowDark = Color(0xFFB8B8BD),
    )

    /** Graphite desktop surfaces retain the same quiet hierarchy in dark mode. */
    val desktopDarkColors: HbColors = HbColors.Dark.copy(
        brand = Color(0xFFBAAFD0),
        primary = Color(0xFFBFB3D4),
        background = Color(0xFF202022),
        surface = Color(0xFF242426),
        textPrimary = Color(0xFFEEEEF0),
        dataViolet = Color(0xFFBDB4CE),
        dataCyan = Color(0xFFC0CDD1),
        shadowLight = Color(0xFF343436),
        shadowDark = Color(0xFF101011),
    )

    val typography: HbTypography = baseTypography.copy(
        body = baseTypography.body.copy(fontSize = 16.sp, lineHeight = 25.sp),
        label = baseTypography.label.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        caption = baseTypography.caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
        metadata = baseTypography.metadata.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )

    /** Dense desktop text with a 1.5 line-height for reading and proportional interface labels. */
    val desktopTypography: HbTypography = baseTypography.copy(
        display = baseTypography.display.copy(fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
        title = baseTypography.title.copy(fontSize = 14.sp, lineHeight = 20.sp),
        body = baseTypography.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
        label = baseTypography.label.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        caption = baseTypography.caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
        metadata = baseTypography.metadata.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )

    /** Applies the host's interface font to conversation text, retaining the dedicated monospace code style. */
    fun typography(fontFamily: FontFamily, isDesktop: Boolean = false): HbTypography {
        val source = if (isDesktop) desktopTypography else typography
        return source.copy(
            display = source.display.copy(fontFamily = fontFamily),
            title = source.title.copy(fontFamily = fontFamily),
            body = source.body.copy(fontFamily = fontFamily),
            label = source.label.copy(fontFamily = fontFamily),
            caption = source.caption.copy(fontFamily = fontFamily),
            metadata = source.metadata.copy(fontFamily = fontFamily),
        )
    }

    val shapes: HbShapes = HbShapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
    )

    val desktopShapes: HbShapes = HbShapes(
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(12.dp),
    )
}
