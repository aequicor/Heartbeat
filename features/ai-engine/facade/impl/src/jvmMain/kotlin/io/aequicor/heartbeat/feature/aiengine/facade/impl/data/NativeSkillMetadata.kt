package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

/**
 * Conservative native skill metadata: closed YAML frontmatter with explicit string name and description.
 * Supports literal scalars and block descriptions; complex or ambiguous YAML is omitted instead of inventing
 * a command name. Imported Claude commands use their separate, permissive metadata and generated manifest.
 */
internal fun nativeSkillMetadata(text: String): Map<String, String>? {
    val lines = text.replace("\r\n", "\n").lines()
    if (lines.firstOrNull() != "---") return null
    val end = lines.drop(1).indexOfFirst { it == "---" }
    if (end < 0) return null
    val fields = NativeSkillFields()
    if (!lines.subList(1, end + 1).all(fields::add)) return null
    return fields.values.takeIf { !it["name"].isNullOrBlank() && !it["description"].isNullOrBlank() }
}

private class NativeSkillFields {
    val values = mutableMapOf<String, String>()
    private var isBlockDescription = false
    private var blockIndent: Int? = null

    fun add(line: String): Boolean = when {
        line.isBlank() || line.startsWith('#') -> true
        line.startsWith(' ') && isBlockDescription -> blockLine(line)
        else -> field(line)
    }

    private fun blockLine(line: String): Boolean {
        val indent = line.takeWhile { it == ' ' }.length
        val minimum = blockIndent ?: indent.also { blockIndent = it }
        if (indent < minimum) return false
        values["description"] = (values["description"].orEmpty() + " " + line.trim()).trim()
        return true
    }

    private fun field(line: String): Boolean {
        isBlockDescription = false
        blockIndent = null
        val key = line.substringBefore(':')
        val isSeparated = line.getOrNull(key.length + 1)?.isWhitespace() == true
        if (!KEY.matches(key) || key in values || !isSeparated) return false
        val raw = line.substringAfter(':', "").trim()
        isBlockDescription = key == "description" && raw in BLOCK_MARKERS
        val value = if (isBlockDescription) NativeScalar("") else scalar(raw)
        return value?.takeIf { key !in setOf("name", "description") || it.isString }?.let {
            values[key] = it.text
            true
        } ?: false
    }
}

private data class NativeScalar(val text: String, val isString: Boolean = true)

private fun scalar(raw: String): NativeScalar? {
    if (raw.startsWith('"') || raw.startsWith('\'')) return quotedScalar(raw)
    val value = raw.substringBefore(" #").trim()
    if (value.isEmpty() || value.first() in "[{}]&*!|>\"'@`#-?:" || ": " in value) return null
    return NativeScalar(value, value !in NON_STRINGS && value.toDoubleOrNull() == null)
}

private fun quotedScalar(raw: String): NativeScalar? = when {
    raw.startsWith('"') -> DOUBLE_QUOTED.matchEntire(raw)?.groupValues?.get(1)
    else -> SINGLE_QUOTED.matchEntire(raw)?.groupValues?.get(1)?.replace("''", "'")
}?.let(::NativeScalar)

// YAML escapes outside this literal subset are deliberately unsupported.
private val DOUBLE_QUOTED = Regex(""""([^"\\]*)"(?:[ \t]+#.*|[ \t]*)""")
private val SINGLE_QUOTED = Regex("""'((?:[^']|'')*)'(?:[ \t]+#.*|[ \t]*)""")

private val KEY = Regex("[A-Za-z][A-Za-z0-9_-]*")
private val BLOCK_MARKERS = setOf("|", "|-", "|+", ">", ">-", ">+")
private val NON_STRINGS = setOf("null", "Null", "NULL", "~", "true", "false", "True", "False", "TRUE", "FALSE")
