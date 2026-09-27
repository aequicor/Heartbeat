package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Theme-independent inline formatting; offsets use Kotlin UTF-16 indices. */
public enum class HbMarkdownStyle { Bold, Italic, Code, Strike, Link }

/** One formatting or link range in [HbMarkdownText]. Links are inert until a caller handles them. */
@Immutable
public data class HbMarkdownSpan(
    val start: Int,
    val end: Int,
    val style: HbMarkdownStyle,
    val destination: String? = null,
)

/** Display text with immutable semantic spans, independent of the currently selected palette. */
@Immutable
public data class HbMarkdownText(val text: String, val spans: ImmutableList<HbMarkdownSpan> = persistentListOf())

/** Each block is small enough to be a separate item in the transcript's lazy list. */
public enum class HbMarkdownBlockKind { Paragraph, Heading, Code, Quote, TableRow, Rule }

/**
 * Prepared Markdown row. [id] is based on source position and chunk number, stable while appending text.
 * Tables have one row per block; [level] is the heading level, or nesting depth for lists and quotes.
 * [codeSpans] preserves lexical context across code chunks; null highlights a manually supplied block.
 */
@Immutable
public data class HbMarkdownBlock(
    val id: String,
    val kind: HbMarkdownBlockKind,
    val content: HbMarkdownText = HbMarkdownText(""),
    val level: Int = 0,
    val marker: String? = null,
    val language: String? = null,
    val cells: ImmutableList<HbMarkdownText> = persistentListOf(),
    val isTableHeader: Boolean = false,
    val codeSpans: ImmutableList<HbCodeSpan>? = null,
)
