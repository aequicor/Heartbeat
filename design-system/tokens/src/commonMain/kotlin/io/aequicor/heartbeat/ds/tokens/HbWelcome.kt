package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cinematic landing tokens; deliberately separate from the compact workspace typography. */
@Immutable
data class HbWelcome(
    val durationMillis: Int = 6000,
    val symbolStart: Float = 2f / 6f,
    val titleStart: Float = 3.5f / 6f,
    val actionsStart: Float = 5.4f / 6f,
    val maxWidth: Dp = 960.dp,
    val symbolSize: Dp = 240.dp,
    val compactSymbolSize: Dp = 160.dp,
    val sectionGap: Dp = 32.dp,
    val pagePadding: Dp = 32.dp,
    val compactPagePadding: Dp = 24.dp,
    val actionHeight: Dp = 48.dp,
    val actionWidth: Dp = 240.dp,
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 48.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1).sp,
    ),
    val compactTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    val brand: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 4.sp,
    ),
)
