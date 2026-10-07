package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.harness.api.HarnessItem

/**
 * One-pass literal substitution: inserted values are never parsed as more template code. The exact declared
 * argument set is required. Invalid placeholders fail without exposing text in diagnostics. Expanded output is
 * bounded to 64 Ki characters before allocation, including repeated arguments, independently of source size.
 */
internal fun renderHarnessTemplate(template: HarnessItem.Template, arguments: Map<String, String>): String {
    require(arguments.keys == template.arguments) { "Template arguments do not match the declaration" }
    val parts = mutableListOf<String>()
    var offset = 0
    var size = 0L
    while (offset < template.body.length) {
        val start = template.body.indexOf("{{", offset)
        val end = if (start < 0) template.body.length else start
        val literal = template.body.substring(offset, end)
        require("}}" !in literal) { "Invalid template placeholder" }
        parts += literal
        size += literal.length
        if (start < 0) break
        val close = template.body.indexOf("}}", start + 2)
        require(close >= 0) { "Invalid template placeholder" }
        val name = template.body.substring(start + 2, close)
        val value = requireNotNull(arguments[name]) { "Template placeholder is not declared" }
        size += value.length
        require(size <= MAX_RENDERED_TEMPLATE_CHARS) { "Rendered template is too long" }
        parts += value
        offset = close + 2
    }
    require(size <= MAX_RENDERED_TEMPLATE_CHARS) { "Rendered template is too long" }
    return parts.joinToString("")
}

internal const val MAX_RENDERED_TEMPLATE_CHARS = 64 * 1024
