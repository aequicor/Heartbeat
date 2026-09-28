package io.aequicor.heartbeat.ds.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import io.aequicor.heartbeat.ds.tokens.HbStudioStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HbStudioFontTest {
    @Test
    fun `studio conversation roles resolve the native interface family with Cyrillic glyphs`() {
        val system = System.getProperty("os.name").orEmpty()
        val expectedFamily = when {
            system.startsWith("Mac", ignoreCase = true) -> ".AppleSystemUIFont"
            system.startsWith("Windows", ignoreCase = true) -> "Segoe UI"
            else -> null
        }
        val font = studioFontResolution()
        assertFalse(font.isFallback)
        if (expectedFamily != null) assertEquals(expectedFamily, font.actualFamily)
        val typography = HbStudioStyle.typography(font.family, isDesktop = true)
        val resolver = createFontFamilyResolver()
        val roles = listOf(
            typography.display,
            typography.title,
            typography.body,
            typography.label,
            typography.caption,
            typography.metadata,
            typography.label.copy(fontWeight = FontWeight.Medium),
        )
        roles.forEach {
            val result = assertIs<FontLoadResult>(
                resolver.resolve(it.fontFamily, it.fontWeight ?: FontWeight.Normal).value,
            )
            val typeface = assertNotNull(result.typeface)
            if (expectedFamily != null) assertEquals(expectedFamily, typeface.familyName)
            assertTrue(typeface.getStringGlyphs("Heartbeat Студия Жё").all { glyph -> glyph != 0.toShort() })
        }
        assertEquals(FontFamily.Monospace, typography.code.fontFamily)
    }

    @Test
    fun `unknown system family is reported honestly and keeps readable native fallback glyphs`() {
        val resolution = resolveStudioFont("HeartbeatMissingFontForFallbackTest")
        assertTrue(resolution.isFallback)
        assertEquals(FontFamily.SansSerif, resolution.family)
        assertNotNull(resolution.actualFamily)
        val loaded = assertIs<FontLoadResult>(createFontFamilyResolver().resolve(resolution.family).value)
        assertTrue(assertNotNull(loaded.typeface).getStringGlyphs("Heartbeat Студия Жё").all { it != 0.toShort() })
    }
}
