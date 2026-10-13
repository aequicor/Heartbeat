package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember

/**
 * A user-activated diagram action, scoped by the enclosing host to the originating chat.
 * PlantUML examples: `rectangle "Docs" [[https://example.com]]`,
 * `rectangle "Insert" [[heartbeat://diagram/insert?text=Hello%20world]]`,
 * `rectangle "Send" [[heartbeat://diagram/send?text=Hello%20world]]`.
 * Text is UTF-8 percent encoded; a send never includes the current draft or attachments.
 */
@Immutable
public data class HbDiagramAction(val kind: HbDiagramActionKind, val text: String)

/** Insert appends to the draft; Send attempts immediate delivery and falls back to the draft when busy. */
public enum class HbDiagramActionKind { Insert, Send }

/** Supplies diagram-only chat actions. Null disables them for read-only transcripts and non-chat hosts. */
@Composable
public fun HbDiagramActionsProvider(onAction: ((HbDiagramAction) -> Unit)?, content: @Composable () -> Unit) {
    val diagrams = currentHbDiagrams
    val scoped = remember(diagrams, onAction) { diagrams?.copy(onAction = onAction) }
    CompositionLocalProvider(LocalHbDiagrams provides scoped, content = content)
}

/** Strictly parses the two supported actions, without accepting a target session, extra parameters or commands. */
internal fun parseDiagramAction(destination: String): HbDiagramAction? {
    val match = destination.takeIf { it.length <= MAX_ACTION_URL_LENGTH }?.let(ActionUrl::matchEntire) ?: return null
    val text = decodeDiagramText(match.groupValues[2]) ?: return null
    val kind = if (match.groupValues[1] == "insert") HbDiagramActionKind.Insert else HbDiagramActionKind.Send
    return HbDiagramAction(kind, text)
}

private val ActionUrl = Regex("""heartbeat://diagram/(insert|send)\?text=([^&#]*)""")
private const val MAX_ACTION_URL_LENGTH = 16 * 1024

private fun decodeDiagramText(encoded: String): String? {
    if (InvalidEscape.containsMatchIn(encoded)) return null
    val raw = EncodedPart.findAll(encoded).flatMap { match ->
        val part = match.value
        if (part.startsWith('%')) {
            part.chunked(ESCAPE_LENGTH).map { it.substring(1).toInt(HEX_RADIX).toByte() }
        } else {
            part.encodeToByteArray().toList()
        }
    }.toList().toByteArray()
    val text = raw.decodeToString()
    return text.takeIf { it.encodeToByteArray().contentEquals(raw) && it.isNotBlank() && it.hasAllowedControls() }
}

private fun String.hasAllowedControls(): Boolean = none { it.isISOControl() && it !in "\n\t\r" }
private val InvalidEscape = Regex("%(?![0-9a-fA-F]{2})")
private val EncodedPart = Regex("(%[0-9a-fA-F]{2})+|[^%]+")
private const val ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16
