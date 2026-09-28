package io.aequicor.heartbeat.ds.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult

internal actual fun hostFontResolution(): HbHostFont {
    val system = System.getProperty("os.name").orEmpty()
    val requestedFamily = when {
        system.startsWith("Mac", ignoreCase = true) -> ".AppleSystemUIFont"
        system.startsWith("Windows", ignoreCase = true) -> "Segoe UI"
        else -> null
    }
    return resolveHostFont(requestedFamily)
}

/** Uses the same resolver as rendered text, so an unavailable system alias is not mistaken for a match. */
@OptIn(ExperimentalTextApi::class)
internal fun resolveHostFont(requestedFamily: String?): HbHostFont {
    val resolver = createFontFamilyResolver()
    val requested = requestedFamily?.let { FontFamily(it) } ?: FontFamily.SansSerif
    val loaded = resolver.resolve(requested).value as? FontLoadResult
    val actualFamily = loaded?.typeface?.familyName
    if (requestedFamily == null || actualFamily == requestedFamily) {
        return HbHostFont(requested, requestedFamily, actualFamily)
    }
    val fallback = FontFamily.SansSerif
    val fallbackLoaded = resolver.resolve(fallback).value as? FontLoadResult
    return HbHostFont(fallback, requestedFamily, fallbackLoaded?.typeface?.familyName)
}
