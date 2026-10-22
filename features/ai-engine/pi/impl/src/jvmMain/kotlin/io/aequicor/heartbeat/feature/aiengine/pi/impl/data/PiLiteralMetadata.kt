package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

/**
 * Conservative literal subset of Pi's YAML metadata, for a command catalog without executing a loader.
 * Invalid or complex frontmatter is omitted from composer discovery, while the panel still lists the file.
 * A missing frontmatter block is valid for a prompt; a skill additionally needs a nonblank description.
 */
internal fun piLiteralMetadata(text: String): Map<String, String>? {
    val lines = text.replace("\r\n", "\n").lines()
    if (lines.firstOrNull()?.trim() != "---") return emptyMap()
    val metadata = lines.drop(1)
    val end = metadata.indexOfFirst { it.trim() == "---" }
    val fields = LiteralFields()
    return if (end >= 0 && metadata.take(end).all(fields::add)) fields.values else null
}

private class LiteralFields {
    val values = mutableMapOf<String, String>()
    private var block: String? = null

    fun add(line: String): Boolean {
        val activeBlock = block
        return when {
            line.isBlank() || line.trimStart().startsWith('#') -> true

            line.startsWith(' ') && activeBlock != null -> {
                values[activeBlock] = (values[activeBlock].orEmpty() + " " + line.trim()).trim()
                true
            }

            else -> field(line)
        }
    }

    private fun field(line: String): Boolean {
        val key = line.substringBefore(':')
        if (!KEY.matches(key) || key in values) return false
        val raw = line.substringAfter(':', "").trim()
        block = key.takeIf { raw in BLOCK_MARKERS }
        val scalar = when {
            block != null -> ""
            key !in setOf("name", "description") && raw in NON_STRINGS -> raw
            else -> literalScalar(raw)
        }
        scalar?.let { values[key] = it }
        return scalar != null
    }
}

private fun literalScalar(raw: String): String? = when {
    raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ->
        raw.substring(1, raw.lastIndex).takeUnless { '\\' in it || '"' in it }

    raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'') ->
        raw.substring(1, raw.lastIndex).takeUnless { '\'' in it.replace("''", "") }?.replace("''", "'")

    raw.isEmpty() || raw.first() in "[{}]&*!|>\"'@`" || ": " in raw -> null

    else -> raw.substringBefore(" #").trim().takeUnless { it in NON_STRINGS || it.toDoubleOrNull() != null }
}

private val KEY = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
private val BLOCK_MARKERS = setOf("|", "|-", "|+", ">", ">-", ">+")
private val NON_STRINGS = setOf("null", "Null", "NULL", "~", "true", "false", "True", "False", "TRUE", "FALSE")
