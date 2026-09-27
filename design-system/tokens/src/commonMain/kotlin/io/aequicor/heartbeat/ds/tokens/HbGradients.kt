package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Restrained pastel blends for decoration; readable text uses semantic solid colors. */
@Immutable
data class HbGradients(val primaryAccent: Brush, val cosmicDeep: Brush) {
    /** Low-saturation gradients coordinated with the pastel surface palette. */
    companion object {
        val Light = HbGradients(
            primaryAccent = Brush.verticalGradient(listOf(Color(0xFFD8CDF3), Color(0xFFC0B3EF))),
            cosmicDeep = Brush.verticalGradient(listOf(Color(0xFFE8E2F5), Color(0xFFD6E3E9))),
        )
        val Dark = HbGradients(
            primaryAccent = Brush.verticalGradient(listOf(Color(0xFFC2B4EE), Color(0xFFA99BCE))),
            cosmicDeep = Brush.verticalGradient(listOf(Color(0xFF363344), Color(0xFF2D3941))),
        )
    }
}
