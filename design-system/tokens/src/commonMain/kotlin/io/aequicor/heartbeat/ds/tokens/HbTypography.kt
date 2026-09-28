package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Platform-font typography that respects system font scaling and supports Cyrillic.
 * Hierarchy rests on weight (400/500/600 only) and color rather than size; no style is smaller than 12sp.
 * [Mobile] and [Desktop] are the host presets; [withFamily] applies the host's interface font.
 */
@Immutable
data class HbTypography(
    val display: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    val code: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    /** Aligned timestamps and counters; prose retains the font's proportional figures. */
    val metadata: TextStyle = caption.copy(fontFeatureSettings = "tnum"),
) {
    /** Applies [fontFamily] to interface text, keeping the dedicated monospace [code] style. */
    fun withFamily(fontFamily: FontFamily): HbTypography = copy(
        display = display.copy(fontFamily = fontFamily),
        title = title.copy(fontFamily = fontFamily),
        body = body.copy(fontFamily = fontFamily),
        label = label.copy(fontFamily = fontFamily),
        caption = caption.copy(fontFamily = fontFamily),
        metadata = metadata.copy(fontFamily = fontFamily),
    )

    /** Host presets: touch reading sizes and dense desktop UI text (13sp UI, 12sp secondary, 14sp reading). */
    companion object {
        private val base = HbTypography()

        val Mobile: HbTypography = base.copy(
            body = base.body.copy(fontSize = 16.sp, lineHeight = 25.sp),
            label = base.label.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
            caption = base.caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
            metadata = base.metadata.copy(fontSize = 13.sp, lineHeight = 18.sp),
        )

        val Desktop: HbTypography = base.copy(
            display = base.display.copy(fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
            title = base.title.copy(fontSize = 14.sp, lineHeight = 20.sp),
            body = base.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
            label = base.label.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
            caption = base.caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            metadata = base.metadata.copy(fontSize = 12.sp, lineHeight = 16.sp),
        )
    }
}
