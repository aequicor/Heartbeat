package io.aequicor.heartbeat.ds.components

internal fun diffFilePath(oldHeader: String, newHeader: String): String? {
    val oldPath = readDiffPath(oldHeader)
    val newPath = readDiffPath(newHeader) ?: return null
    val selected = if (newPath == "/dev/null") oldPath else newPath
    if (selected == null || selected == "/dev/null") return null
    val hasGitPrefixes = when {
        newPath == "/dev/null" -> selected.startsWith("a/")
        oldPath == "/dev/null" -> newPath.startsWith("b/")
        else -> oldPath?.startsWith("a/") == true && newPath.startsWith("b/")
    }
    return selected.drop(if (hasGitPrefixes) GIT_SIDE_PREFIX_LENGTH else 0).takeIf { it.isNotBlank() }
}

private fun readDiffPath(header: String): String? {
    val path = if (header.startsWith('"')) readQuotedDiffPath(header) else header.substringBefore('\t')
    return path?.takeIf { it.isNotBlank() }
}

private fun readQuotedDiffPath(header: String): String? {
    val path = StringBuilder()
    var position = 1
    while (position < header.length) {
        when (val character = header[position]) {
            '"' -> return path.toString()

            '\\' -> {
                val escape = diffPathEscape(header, position) ?: break
                path.append(escape.text)
                position = escape.end
            }

            else -> {
                path.append(character)
                position++
            }
        }
    }
    return null
}

private data class DiffPathEscape(val text: String, val end: Int)

private fun diffPathEscape(header: String, position: Int): DiffPathEscape? {
    val character = header.getOrNull(position + 1) ?: return null
    if (character in '0'..'7') return octalDiffPathEscape(header, position)
    val value = when (character) {
        '\\', '"' -> character.toString()
        't' -> "\t"
        'n' -> "\n"
        'r' -> "\r"
        else -> "\\$character"
    }
    return DiffPathEscape(value, position + ESCAPE_LENGTH)
}

private fun octalDiffPathEscape(header: String, start: Int): DiffPathEscape? {
    val bytes = mutableListOf<Byte>()
    var position = start
    while (header.getOrNull(position) == '\\' && header.getOrNull(position + 1)?.let { it in '0'..'7' } == true) {
        position++
        var value = 0
        var digits = 0
        while (digits < OCTAL_DIGITS && header.getOrNull(position)?.let { it in '0'..'7' } == true) {
            value = value * OCTAL_RADIX + (header[position] - '0')
            position++
            digits++
        }
        if (value > MAX_BYTE_VALUE) return null
        bytes += value.toByte()
    }
    val encoded = bytes.toByteArray()
    val decoded = encoded.decodeToString()
    return if (decoded.encodeToByteArray().contentEquals(encoded)) DiffPathEscape(decoded, position) else null
}

private const val GIT_SIDE_PREFIX_LENGTH = 2
private const val ESCAPE_LENGTH = 2
private const val OCTAL_DIGITS = 3
private const val OCTAL_RADIX = 8
private const val MAX_BYTE_VALUE = 255
