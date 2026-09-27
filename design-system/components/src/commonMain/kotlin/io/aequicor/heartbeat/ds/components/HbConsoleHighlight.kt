package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal enum class HbConsoleTone { Command, Info, Success, Warning, Error }

/** Exclusive-end UTF-16 range covering a classified line, without its line terminator. */
@Immutable
internal data class HbConsoleSpan(val start: Int, val end: Int, val tone: HbConsoleTone)

/**
 * Classifies anchored console prefixes after optional horizontal indentation in linear time.
 * Input, including CRLF and incomplete final lines, stays unchanged. Unknown lines have no span;
 * ANSI sequences, terminal control commands and embedded prose are not interpreted.
 */
internal fun highlightHbConsole(source: String): ImmutableList<HbConsoleSpan> {
    val spans = mutableListOf<HbConsoleSpan>()
    var start = 0
    while (start < source.length) {
        val end = source.consoleLineEnd(start)
        source.consoleTone(start, end)?.let { spans += HbConsoleSpan(start, end, it) }
        start = source.afterConsoleLine(end)
    }
    return spans.toImmutableList()
}

private fun String.consoleLineEnd(start: Int): Int {
    var end = start
    while (end < length && this[end] != '\r' && this[end] != '\n') end++
    return end
}

private fun String.afterConsoleLine(end: Int): Int {
    var next = end
    if (getOrNull(next) == '\r') next++
    if (getOrNull(next) == '\n') next++
    return next
}

private fun String.consoleTone(start: Int, end: Int): HbConsoleTone? {
    val contentStart = skipConsoleSpaces(start, end)
    if (contentStart == end) return null
    if (isConsoleCommand(contentStart, end)) return HbConsoleTone.Command
    if (hasConsolePrefix(contentStart, end, EXIT_CODE_PREFIX)) return exitCodeTone(contentStart, end)
    return ConsolePrefixes.firstOrNull { hasConsolePrefix(contentStart, end, it.first) }?.second
}

private fun String.isConsoleCommand(start: Int, end: Int): Boolean {
    if (this[start] == '$' && start + 1 < end && this[start + 1].isConsoleSpace()) return true
    val promptEnd = start + POWERSHELL_PREFIX.length
    if (!hasConsolePrefix(start, end, POWERSHELL_PREFIX) || promptEnd >= end || !this[promptEnd].isConsoleSpace()) {
        return false
    }
    var position = promptEnd
    while (position + 1 < end) {
        if (this[position] == '>' && this[position + 1].isConsoleSpace()) return true
        position++
    }
    return false
}

private fun String.hasConsolePrefix(start: Int, end: Int, prefix: String): Boolean {
    if (end - start < prefix.length || !regionMatches(start, prefix, 0, prefix.length, ignoreCase = true)) return false
    val next = start + prefix.length
    if (next == end || !prefix.last().isLetterOrDigit()) return true
    return !this[next].isLetterOrDigit() && this[next] != '_'
}

private fun String.exitCodeTone(start: Int, end: Int): HbConsoleTone? {
    var position = skipConsoleSpaces(start + EXIT_CODE_PREFIX.length, end)
    if (position < end && (this[position] == '-' || this[position] == '+')) position++
    val digitStart = position
    var isZero = true
    while (position < end && this[position] in '0'..'9') {
        if (this[position] != '0') isZero = false
        position++
    }
    if (position == digitStart || skipConsoleSpaces(position, end) != end) return null
    return if (isZero) HbConsoleTone.Success else HbConsoleTone.Error
}

private fun String.skipConsoleSpaces(start: Int, end: Int): Int {
    var position = start
    while (position < end && this[position].isConsoleSpace()) position++
    return position
}

private fun Char.isConsoleSpace(): Boolean = this == ' ' || this == '\t'

private const val EXIT_CODE_PREFIX = "exit code:"
private const val POWERSHELL_PREFIX = "PS"
private val ConsolePrefixes = listOf(
    "> Task" to HbConsoleTone.Info,
    "BUILD SUCCESSFUL" to HbConsoleTone.Success,
    "BUILD FAILED" to HbConsoleTone.Error,
    "FAILURE:" to HbConsoleTone.Error,
    "ERROR" to HbConsoleTone.Error,
    "WARN" to HbConsoleTone.Warning,
    "warning:" to HbConsoleTone.Warning,
    "INFO" to HbConsoleTone.Info,
    "[INFO]" to HbConsoleTone.Info,
)
