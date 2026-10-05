package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.depth
import io.aequicor.heartbeat.feature.organicai.api.isZygote
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** `c3 "scout"`, or `the zygote`. */
internal fun Cell.label(): String = if (isZygote) "the zygote" else "${id.value} \"$name\""

/** One line per cell: id, name, parent, generation and state. */
internal fun Organism.cellTable(): String = cells.joinToString("\n") { cell ->
    val parent = cell.parent?.let { "child of ${it.value}" } ?: "zygote"
    "- ${cell.id.value} \"${cell.name}\" ($parent, generation ${depth(cell.id)}): ${cell.phase.describe()}"
}

/** Open cases, one per line, or a note that there are none. */
internal fun Organism.caseTable(): String = cases.joinToString("\n") { case ->
    when (case) {
        is ImmuneCase.Complaint -> "- ${case.id.value}: ${case.plaintiff.value} accused ${case.accused.value}"
        is ImmuneCase.Dispute -> "- ${case.id.value}: dispute asked by ${case.asker.value}"
    }
}.ifEmpty { "- none" }

internal fun CellPhase.describe(): String = when (this) {
    is CellPhase.Working -> if (awaiting.isEmpty()) "working" else "working, awaiting the user's decision"
    CellPhase.Resting -> "resting, waiting for its children or a ruling"
    is CellPhase.Stalled -> "stalled: ${breakdown.describe()}"
    is CellPhase.Completed -> "completed"
    is CellPhase.Dead -> "ended: ${cause.describe()}"
}

internal fun DeathCause.describe(): String = when (this) {
    is DeathCause.Lysed -> "killed by the immune system (case ${case.value}): ${brief(reason)}"
    is DeathCause.Failed -> "its turn failed: ${breakdown.describe()}"
    is DeathCause.Orphaned -> "ended together with its ancestor ${ancestor.value}"
    DeathCause.Aborted -> "the organism was aborted"
}

internal fun Breakdown.describe(): String = when (this) {
    Breakdown.NoModel -> "no model is selected"
    is Breakdown.Engine -> "engine failure ${failure.code}"
    Breakdown.Interrupted -> "the turn was cancelled outside the organism"
    Breakdown.Unconfirmed -> "the engine could not confirm how the turn ended"
}

/** [text] cut to [max] characters with a visible mark. */
internal fun cut(text: String, max: Int): String =
    if (text.length <= max) text else text.take((max - CUT_MARK.length).coerceAtLeast(0)) + CUT_MARK

/**
 * The marker around text other sessions wrote, in one prompt. Its nonce is fresh for every prompt and never left in
 * the text it wraps, so nothing a cell, user or judge wrote can close the fence and go on as the host, however it
 * spells or disguises a marker.
 */
internal class Fence(private val nonce: String) {
    init {
        require(nonce.isNotEmpty() && nonce.all(Char::isLetterOrDigit)) { "A fence nonce is letters and digits" }
    }

    /** The line that opens fenced text. */
    val open: String = "<<<$nonce"

    /** The line that closes it. */
    val close: String = "$nonce>>>"

    /** How a reader tells fenced text apart. */
    val lines: String = "between a line \"$open\" and a line \"$close\""

    /** [text] between [open] and [close]. */
    fun wrap(text: String): String = "$open\n${text.without(nonce)}\n$close"

    companion object {
        /** A fence no earlier text could know. */
        @OptIn(ExperimentalUuidApi::class)
        fun random(): Fence = Fence(Uuid.random().toHexString().take(NONCE_CHARS))
    }
}

private tailrec fun String.without(nonce: String): String =
    if (nonce in this) replace(nonce, "").without(nonce) else this

/**
 * [text] of another session as one short line, for one-line descriptions: line breaks, control and invisible
 * format characters (also those outside the basic plane, such as tag characters) become spaces, so it cannot break
 * the line it is shown in.
 */
internal fun brief(text: String): String {
    val line = spacedOut(text).split(' ').filter(String::isNotEmpty).joinToString(" ")
    return if (line.length <= BRIEF_CHARS) line else line.cutBefore(BRIEF_CHARS - 1) + "…"
}

private fun spacedOut(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val width = if (isPairAt(text, index)) 2 else 1
        append(if (isBreakingAt(text, index, width)) " " else text.substring(index, index + width))
        index += width
    }
}

private fun isPairAt(text: String, index: Int): Boolean =
    text[index].isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true

private fun isBreakingAt(text: String, index: Int, width: Int): Boolean {
    if (width == 1) return text[index].isBreaking()
    val code = (text[index].code - HIGH_SURROGATES shl SURROGATE_BITS) + (text[index + 1].code - LOW_SURROGATES)
    return ASTRAL_FORMATS.any { code + ASTRAL_START in it }
}

private fun Char.isBreaking(): Boolean =
    isWhitespace() || isISOControl() || isSurrogate() || category == CharCategory.FORMAT

/** The first [length] characters, one fewer when the cut would leave half a surrogate pair. */
private fun String.cutBefore(length: Int): String {
    val kept = take(length)
    return if (kept.last().isHighSurrogate()) kept.dropLast(1) else kept
}

private const val CUT_MARK = "\n…[cut]"
private const val BRIEF_CHARS = 200
private const val NONCE_CHARS = 12
private const val HIGH_SURROGATES = 0xD800
private const val LOW_SURROGATES = 0xDC00
private const val SURROGATE_BITS = 10
private const val ASTRAL_START = 0x10000

/** Invisible format characters outside the basic plane: shorthand format controls, musical format and tags. */
private val ASTRAL_FORMATS = listOf(0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE007F)
