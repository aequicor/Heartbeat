package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle

internal val COMPOSER_SKILL_LINK = Regex("\\[([/@$][A-Za-z0-9_][A-Za-z0-9_.:-]*)]\\(([^\\s()]+/SKILL\\.md)\\)")
private val REFERENCE_TOKEN = Regex("(?:^|\\s)([@$][A-Za-z0-9_][A-Za-z0-9_.:/-]*|/[A-Za-z0-9_][A-Za-z0-9_.:-]*)")

/**
 * Displays project skill links by their short label and colours command, skill and tool mention syntax.
 * Only presentation changes: the stored draft, clipboard and sent prompt retain the instruction path.
 * Foundation's output transformation maintains caret and selection mapping across the hidden link target.
 */
internal fun composerReferenceTransformation(color: Color): OutputTransformation = OutputTransformation {
    val links = COMPOSER_SKILL_LINK.findAll(asCharSequence()).toList()
    for (link in links.asReversed()) {
        val label = link.groups[1] ?: continue
        replace(label.range.last + 1, link.range.last + 1, "")
        replace(link.range.first, label.range.first, "")
    }
    REFERENCE_TOKEN.findAll(asCharSequence()).forEach { match ->
        val token = match.groups[1] ?: return@forEach
        addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
    }
}
