package io.aequicor.heartbeat.chromiumplugin.panel

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.aequicor.heartbeat.chromiumplugin.panel.resources.Res
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Fonts of the canvas, which sees no system fonts: Compose's default family covers Latin and Cyrillic only.
 * Fallback families (emoji, symbols, math) fill missing glyphs once preloaded; [code] replaces the monospace family
 * the design system uses for code. The files come from `preparePanelFonts` (Noto, SIL OFL 1.1).
 */
internal class PanelFonts(val fallbacks: List<FontFamily>, val code: FontFamily?) {
    companion object {
        /** Reads every font of the extension in parallel. */
        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(): PanelFonts = coroutineScope {
            val fallbacks = FALLBACKS.map { name -> async { family(name) } }
            val code = async { family(CODE) }
            PanelFonts(fallbacks.awaitAll(), code.await())
        }

        @OptIn(ExperimentalResourceApi::class)
        private suspend fun family(name: String) = FontFamily(Font(name, Res.readBytes("files/fonts/$name.ttf")))

        private val FALLBACKS = listOf("Noto-COLRv1", "NotoSansSymbols2-Regular", "NotoSansMath-Regular")
        private const val CODE = "NotoSansMono-Regular"
    }
}
