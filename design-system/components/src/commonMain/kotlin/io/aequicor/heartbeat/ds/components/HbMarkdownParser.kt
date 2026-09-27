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
 * CRLF and CR line endings are normalized first: the parser only recognizes `\n` line ends.
 * Container nesting deeper than a fixed bound renders as literal text instead of recursing.
 */
public fun parseHbMarkdown(source: String): ImmutableList<HbMarkdownBlock> {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(normalized)
    return MarkdownBlockParser(normalized).parse(tree)
}

/** Block and inline walkers recurse per nesting level; hostile input must not overflow the UI thread stack. */
internal const val MAX_MARKDOWN_NESTING = 32

private class MarkdownBlockParser(private val source: String) {
    private val rows = mutableListOf<HbMarkdownBlock>()
    private val inline = HbMarkdownInlineParser(source)

    fun parse(tree: ASTNode): ImmutableList<HbMarkdownBlock> {
        tree.children.forEach { visit(it) }
        return rows.toImmutableList()
    }

    private fun visit(node: ASTNode, depth: Int = 0, marker: String? = null, listDepth: Int = 0, nesting: Int = 0) {
        if (nesting > MAX_MARKDOWN_NESTING) {
            append(node, HbMarkdownBlockKind.Paragraph, HbMarkdownText(node.raw(source)))
            return
        }
        when (node.type) {
            Elements.UNORDERED_LIST, Elements.ORDERED_LIST -> visitList(node, depth, listDepth, nesting)

            Elements.BLOCK_QUOTE -> node.children.forEach {
                visit(it, depth + 1, listDepth = listDepth, nesting = nesting + 1)
            }

            Elements.CODE_FENCE, Elements.CODE_BLOCK -> code(node)

            GFMElementTypes.TABLE -> table(node)

            Tokens.HORIZONTAL_RULE -> rows.add(HbMarkdownBlock("${node.startOffset}:0", HbMarkdownBlockKind.Rule))

            Elements.PARAGRAPH -> paragraph(node, depth, marker, listDepth)

            Elements.HTML_BLOCK -> append(node, HbMarkdownBlockKind.Paragraph, HbMarkdownText(node.raw(source)))

            in HeadingTypes -> heading(node)

            else -> Unit
        }
    }

    private fun visitList(node: ASTNode, depth: Int, listDepth: Int, nesting: Int) {
        node.children.filter { it.type == Elements.LIST_ITEM }.forEach { item ->
            val marker = listMarker(item)
            var isFirst = true
            item.children.forEach { child ->
                val isNestedList = child.type == Elements.UNORDERED_LIST || child.type == Elements.ORDERED_LIST
                val childListDepth = listDepth + if (isNestedList) 1 else 0
                visit(child, depth, if (isFirst) marker else null, childListDepth, nesting = nesting + 1)
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
        val chunks = chunkHbCode(code, language)
        chunks.forEachIndexed { index, chunk ->
            rows += HbMarkdownBlock(
                id = "${node.startOffset}:$index",
                kind = HbMarkdownBlockKind.Code,
                content = HbMarkdownText(chunk.text),
                language = language,
                codeSpans = chunk.spans,
                isFirstSegment = index == 0,
                isLastSegment = index == chunks.lastIndex,
            )
        }
    }

    /**
     * Keeps only content lines: container prefixes (`> `, list indentation) are separate tokens, and the
     * fence's own indentation is removed from each line as CommonMark requires.
     */
    private fun fencedCode(node: ASTNode): String {
        val fenceIndent = node.children.firstOrNull { it.type == Tokens.CODE_FENCE_START }
            ?.raw(source)?.takeWhile { it == ' ' }?.length ?: 0
        val code = StringBuilder()
        var isContent = false
        for (child in node.children) {
            when {
                child.type == Tokens.CODE_FENCE_END -> break

                child.type == Tokens.EOL -> if (isContent) code.append('\n') else isContent = true

                isContent && child.type == Tokens.CODE_FENCE_CONTENT ->
                    code.append(child.raw(source).dropIndent(fenceIndent))
            }
        }
        return code.toString().trimEnd('\n')
    }

    private fun indentedCode(node: ASTNode): String = node.children
        .filter { it.type == Tokens.CODE_LINE || it.type == Tokens.EOL }
        .joinToString("") { if (it.type == Tokens.CODE_LINE) it.raw(source).dropIndent(CODE_BLOCK_INDENT) else "\n" }
        .trimEnd('\n')

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

private const val CODE_BLOCK_INDENT = 4

/** Removes up to [columns] leading spaces, keeping deeper relative indentation. */
private fun String.dropIndent(columns: Int): String {
    var start = 0
    while (start < length && start < columns && this[start] == ' ') start++
    return substring(start)
}
