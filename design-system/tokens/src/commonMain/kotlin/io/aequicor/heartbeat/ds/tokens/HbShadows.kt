package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Paired upper-left light and lower-right dark shadows describe soft raised and inset surfaces. */
@Immutable
data class HbShadows(
    val light: Color = HbColors.Light.shadowLight,
    val dark: Color = HbColors.Light.shadowDark,
    val blurRadius: Dp = 12.dp,
    val offset: Dp = 6.dp,
    val pressedBlurRadius: Dp = 4.dp,
    val pressedOffset: Dp = 2.dp,
) {
    /** Default shadow recipes matched to the light and dark surfaces. */
    companion object {
        val Light = HbShadows()
        val Dark = HbShadows(light = HbColors.Dark.shadowLight, dark = HbColors.Dark.shadowDark)
    }
}
