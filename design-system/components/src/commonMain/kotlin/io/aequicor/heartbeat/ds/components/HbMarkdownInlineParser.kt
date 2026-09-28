package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.toImmutableList
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.MarkdownElementTypes as Elements
import org.intellij.markdown.MarkdownTokenTypes as Tokens

internal class HbMarkdownInlineParser(private val source: String) {
    fun parse(node: ASTNode): HbMarkdownText {
        val writer = InlineWriter(source)
        writer.append(node)
        return writer.result()
    }
}

private class InlineWriter(private val source: String) {
    private val text = StringBuilder()
    private val spans = mutableListOf<HbMarkdownSpan>()

    fun result(): HbMarkdownText = HbMarkdownText(text.toString(), spans.toImmutableList())

    private var depth = 0

    fun append(node: ASTNode) {
        if (depth >= MAX_MARKDOWN_NESTING) {
            text.append(unescapeMarkdown(node.raw(source)))
            return
        }
        depth++
        try {
            appendNode(node)
        } finally {
            depth--
        }
    }

    private fun appendNode(node: ASTNode) {
        when (node.type) {
            Elements.STRONG -> styled(node, HbMarkdownStyle.Bold)
            Elements.EMPH -> styled(node, HbMarkdownStyle.Italic)
            GFMElementTypes.STRIKETHROUGH -> styled(node, HbMarkdownStyle.Strike)
            Elements.CODE_SPAN -> code(node)
            Elements.INLINE_LINK -> link(node)
            Elements.IMAGE -> image(node)
            Elements.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> autoLink(node)
            Elements.LINK_TEXT -> children(node, skipBrackets = true)
            Tokens.HARD_LINE_BREAK -> text.append('\n')
            Tokens.EOL -> softLineBreak()
            Tokens.BLOCK_QUOTE -> Unit
            else -> if (node.children.isEmpty()) text.append(unescapeMarkdown(node.raw(source))) else children(node)
        }
    }

    private fun softLineBreak() {
        if (text.lastOrNull() != '\n') text.append(' ')
    }

    private fun children(node: ASTNode, skipBrackets: Boolean = false, delimiters: Int = 0) {
        val lastContent = node.children.size - delimiters
        node.children.forEachIndexed { index, child ->
            val isBracket = child.type == Tokens.LBRACKET || child.type == Tokens.RBRACKET
            val isDelimiter = index < delimiters || index >= lastContent
            if (!(skipBrackets && isBracket) && !isDelimiter) append(child)
        }
    }

    /** Skips only the opening and closing delimiters; unmatched `*`, `_` or `~` inside remain literal text. */
    private fun styled(node: ASTNode, style: HbMarkdownStyle) {
        val start = text.length
        val maxDelimiters = if (node.type == Elements.EMPH) 1 else 2
        val opening = node.children.takeWhile { it.isDelimiter() }.size.coerceAtMost(maxDelimiters)
        val closing = node.children.takeLastWhile { it.isDelimiter() }.size.coerceAtMost(maxDelimiters)
        children(node, delimiters = minOf(opening, closing))
        addSpan(start, style)
    }

    private fun ASTNode.isDelimiter(): Boolean = type == Tokens.EMPH || type == GFMTokenTypes.TILDE

    private fun code(node: ASTNode) {
        val start = text.length
        val raw = node.raw(source)
        val ticks = raw.takeWhile { it == '`' }.length
        var code = raw.substring(ticks, raw.length - ticks).replace('\n', ' ')
        if (code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) code = code.drop(1).dropLast(1)
        text.append(code)
        addSpan(start, HbMarkdownStyle.Code)
    }

    private fun link(node: ASTNode) {
        val start = text.length
        val label = node.children.firstOrNull { it.type == Elements.LINK_TEXT }
        if (label != null) append(label)
        val destination = node.children.firstOrNull { it.type == Elements.LINK_DESTINATION }?.raw(source)
        safeMarkdownDestination(destination)?.let { addSpan(start, HbMarkdownStyle.Link, it) }
    }

    private fun image(node: ASTNode) {
        val link = node.children.firstOrNull { it.type == Elements.INLINE_LINK }
        val label = link?.children?.firstOrNull { it.type == Elements.LINK_TEXT }
        if (label != null) append(label) else text.append(node.raw(source))
    }

    private fun autoLink(node: ASTNode) {
        val value = node.raw(source).removePrefix("<").removeSuffix(">")
        val start = text.length
        text.append(value)
        safeMarkdownDestination(value)?.let { addSpan(start, HbMarkdownStyle.Link, it) }
    }

    private fun addSpan(start: Int, style: HbMarkdownStyle, destination: String? = null) {
        if (text.length > start) spans += HbMarkdownSpan(start, text.length, style, destination)
    }
}

internal fun safeMarkdownDestination(destination: String?): String? {
    val value = destination?.trim()?.removeSurrounding("<", ">") ?: return null
    val scheme = value.substringBefore(':', "").lowercase()
    return value.takeIf { it.isNotBlank() && (scheme in SafeLinkSchemes || it.startsWith('#') || it.startsWith('/')) }
}

private val SafeLinkSchemes = setOf("https", "http", "mailto")
private val MarkdownEscape = Regex("\\\\([!\"#$%&'()*+,\\-./:;<=>?@\\[\\]\\\\^_`{|}~])")

private fun unescapeMarkdown(text: String): String = MarkdownEscape.replace(text) { it.groupValues[1] }
