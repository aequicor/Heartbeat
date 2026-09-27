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

    fun append(node: ASTNode) {
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
            Tokens.BLOCK_QUOTE -> Unit
            else -> if (node.children.isEmpty()) text.append(unescapeMarkdown(node.raw(source))) else children(node)
        }
    }

    private fun children(node: ASTNode, skipBrackets: Boolean = false, skipMarkers: Boolean = false) {
        node.children.forEach { child ->
            val isBracket = child.type == Tokens.LBRACKET || child.type == Tokens.RBRACKET
            val isMarker = child.type == Tokens.EMPH || child.type == GFMTokenTypes.TILDE
            val isSkippedBracket = skipBrackets && isBracket
            val isSkippedMarker = skipMarkers && isMarker
            if (!isSkippedBracket && !isSkippedMarker) append(child)
        }
    }

    private fun styled(node: ASTNode, style: HbMarkdownStyle) {
        val start = text.length
        children(node, skipMarkers = true)
        addSpan(start, style)
    }

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
