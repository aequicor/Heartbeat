package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.MarkdownElementTypes as Elements
import org.intellij.markdown.MarkdownTokenTypes as Tokens

/**
 * Parses CommonMark/GFM into immutable, bounded display rows. Call during model preparation, not per row.
 * Unfinished fences remain code while streaming. HTML and images never execute or fetch resources.
 */
public fun parseHbMarkdown(source: String): ImmutableList<HbMarkdownBlock> {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    return MarkdownBlockParser(source).parse(tree)
}

private class MarkdownBlockParser(private val source: String) {
    private val rows = mutableListOf<HbMarkdownBlock>()
    private val inline = HbMarkdownInlineParser(source)

    fun parse(tree: ASTNode): ImmutableList<HbMarkdownBlock> {
        tree.children.forEach { visit(it) }
        return rows.toImmutableList()
    }

    private fun visit(node: ASTNode, depth: Int = 0, marker: String? = null, listDepth: Int = 0) {
        when (node.type) {
            Elements.UNORDERED_LIST, Elements.ORDERED_LIST -> visitList(node, depth, listDepth)
            Elements.BLOCK_QUOTE -> node.children.forEach { visit(it, depth + 1, listDepth = listDepth) }
            Elements.CODE_FENCE, Elements.CODE_BLOCK -> code(node)
            GFMElementTypes.TABLE -> table(node)
            Tokens.HORIZONTAL_RULE -> rows.add(HbMarkdownBlock("${node.startOffset}:0", HbMarkdownBlockKind.Rule))
            Elements.PARAGRAPH -> paragraph(node, depth, marker, listDepth)
            Elements.HTML_BLOCK -> append(node, HbMarkdownBlockKind.Paragraph, HbMarkdownText(node.raw(source)))
            in HeadingTypes -> heading(node)
            else -> Unit
        }
    }

    private fun visitList(node: ASTNode, depth: Int, listDepth: Int) {
        node.children.filter { it.type == Elements.LIST_ITEM }.forEach { item ->
            val marker = listMarker(item)
            var isFirst = true
            item.children.forEach { child ->
                val nesting = if (child.type == Elements.UNORDERED_LIST || child.type == Elements.ORDERED_LIST) 1 else 0
                visit(child, depth, if (isFirst) marker else null, listDepth + nesting)
                if (child.type == Elements.PARAGRAPH) isFirst = false
            }
        }
    }

    private fun listMarker(item: ASTNode): String {
        val checkbox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
        if (checkbox != null) return if (checkbox.raw(source).contains('x', ignoreCase = true)) "☑" else "☐"
        return item.children.firstOrNull { it.type == Tokens.LIST_NUMBER }?.raw(source)?.trim() ?: "•"
    }

    private fun paragraph(node: ASTNode, depth: Int, marker: String?, listDepth: Int) {
        val kind = if (depth > 0) HbMarkdownBlockKind.Quote else HbMarkdownBlockKind.Paragraph
        append(node, kind, inline.parse(node), maxOf(depth, listDepth), marker)
    }

    private fun heading(node: ASTNode) {
        val content = node.children.firstOrNull { it.type == Tokens.ATX_CONTENT || it.type == Tokens.SETEXT_CONTENT }
        append(node, HbMarkdownBlockKind.Heading, inline.parse(content ?: node).trimmed(), headingLevel(node.type))
    }

    private fun code(node: ASTNode) {
        val language = node.children.firstOrNull { it.type == Tokens.FENCE_LANG }?.raw(source)?.trim()
        val code = if (node.type == Elements.CODE_FENCE) fencedCode(node) else indentedCode(node)
        chunkHbCode(code, language).forEachIndexed { index, chunk ->
            rows += HbMarkdownBlock(
                id = "${node.startOffset}:$index",
                kind = HbMarkdownBlockKind.Code,
                content = HbMarkdownText(chunk.text),
                language = language,
                codeSpans = chunk.spans,
            )
        }
    }

    private fun fencedCode(node: ASTNode): String {
        val raw = node.raw(source)
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0) return ""
        val closing = node.children.firstOrNull { it.type == Tokens.CODE_FENCE_END }?.startOffset ?: node.endOffset
        return source.substring(node.startOffset + firstLineEnd + 1, closing).trimEnd('\n', '\r')
    }

    private fun indentedCode(node: ASTNode): String = node.children
        .filter { it.type == Tokens.CODE_LINE || it.type == Tokens.EOL }
        .joinToString("") { it.raw(source) }
        .trimEnd('\n', '\r')

    private fun table(node: ASTNode) {
        node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }.forEach { row ->
            val cells = row.children.filter { it.type == GFMTokenTypes.CELL }.map { inline.parse(it).trimmed() }
            val chunks = cells.map(::chunkHbText)
            val chunkCount = chunks.maxOfOrNull { it.size } ?: 0
            repeat(chunkCount) { index ->
                rows += HbMarkdownBlock(
                    id = "${row.startOffset}:$index",
                    kind = HbMarkdownBlockKind.TableRow,
                    cells = chunks.map { it.getOrElse(index) { HbMarkdownText("") } }.toImmutableList(),
                    isTableHeader = row.type == GFMElementTypes.HEADER,
                )
            }
        }
    }

    private fun append(
        node: ASTNode,
        kind: HbMarkdownBlockKind,
        text: HbMarkdownText,
        level: Int = 0,
        marker: String? = null,
    ) {
        chunkHbText(text).forEachIndexed { index, chunk ->
            rows += HbMarkdownBlock("${node.startOffset}:$index", kind, chunk, level, if (index == 0) marker else null)
        }
    }
}

private val HeadingTypes = listOf(
    Elements.ATX_1,
    Elements.ATX_2,
    Elements.ATX_3,
    Elements.ATX_4,
    Elements.ATX_5,
    Elements.ATX_6,
    Elements.SETEXT_1,
    Elements.SETEXT_2,
)

private fun headingLevel(type: IElementType): Int = when (type) {
    Elements.SETEXT_1 -> 1
    Elements.SETEXT_2 -> 2
    else -> HeadingTypes.indexOf(type) + 1
}

internal fun ASTNode.raw(source: String): String = source.substring(startOffset, endOffset)
