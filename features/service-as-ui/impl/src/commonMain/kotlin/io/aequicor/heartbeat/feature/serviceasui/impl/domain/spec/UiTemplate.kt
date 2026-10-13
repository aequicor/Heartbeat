package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

/** Where a binding path starts. */
internal enum class UiPathRoot(val key: String) {
    /** Results of data sources: `data.<source>…`. */
    Data("data"),

    /** Local state of the surface: `state.<key>…`. */
    State("state"),

    /** The current element inside a list or a table. */
    Item("item"),

    /** Position of the current element inside a list or a table. */
    Index("index"),

    /** Arguments the surface or modal was opened with. */
    Args("args"),

    /** Load status of data sources: `status.<source>.loading|failed|ready|error`. */
    Status("status"),

    /** Facts about where the surface runs: `context.platform`, `context.hasSession`. */
    Context("context"),
    ;

    companion object {
        /** The root named [key], or null. */
        fun of(key: String): UiPathRoot? = entries.firstOrNull { it.key == key }
    }
}

/** One step of a path after its root. */
internal sealed interface UiPathSegment {
    /** An object key. */
    data class Key(val name: String) : UiPathSegment

    /** An array position. */
    data class Index(val index: Int) : UiPathSegment
}

/** A path such as `data.prs.items[0].title`. */
internal data class UiPath(val root: UiPathRoot, val segments: List<UiPathSegment> = emptyList()) {
    /** First key after the root: the source of `data.prs`, the state key of `state.filter`. */
    val head: String? get() = (segments.firstOrNull() as? UiPathSegment.Key)?.name

    override fun toString(): String = buildString {
        append(root.key)
        for (segment in segments) {
            when (segment) {
                is UiPathSegment.Key -> append('.').append(segment.name)
                is UiPathSegment.Index -> append('[').append(segment.index).append(']')
            }
        }
    }
}

/** One part of a template: literal text or a binding. */
internal sealed interface UiTemplatePart {
    /** Literal text. */
    data class Text(val value: String) : UiTemplatePart

    /** The value at [path]. */
    data class Binding(val path: UiPath) : UiTemplatePart
}

/** A string with `{{path}}` bindings; `\{{` is a literal `{{`. */
internal data class UiTemplate(val parts: List<UiTemplatePart>) {
    /** The path when the whole string is one binding: its value keeps its JSON type. */
    val single: UiPath? get() = (parts.singleOrNull() as? UiTemplatePart.Binding)?.path

    /** Every bound path, in order. */
    val paths: List<UiPath> get() = parts.mapNotNull { (it as? UiTemplatePart.Binding)?.path }
}

/** Outcome of [parseUiTemplate]. */
internal sealed interface UiTemplateParse {
    /** A valid [template]. */
    data class Parsed(val template: UiTemplate) : UiTemplateParse

    /** Not a valid template, for [reason]. */
    data class Invalid(val reason: String) : UiTemplateParse
}

/** Parses [text] into literal parts and bindings. */
internal fun parseUiTemplate(text: String): UiTemplateParse {
    val reader = TemplateReader(text)
    while (!reader.isDone) reader.step()
    return reader.result()
}

/** Reads a template one literal run, escape or binding at a time. */
private class TemplateReader(private val text: String) {
    private val parts = mutableListOf<UiTemplatePart>()
    private val buffer = StringBuilder()
    private var index = 0
    private var error: String? = null

    val isDone: Boolean get() = index >= text.length || error != null

    fun step() {
        val open = text.indexOf(OPEN, index)
        when {
            open < 0 -> take(text.length)
            open > 0 && text[open - 1] == ESCAPE -> escape(open)
            else -> bind(open)
        }
    }

    fun result(): UiTemplateParse {
        flush()
        return error?.let { UiTemplateParse.Invalid(it) } ?: UiTemplateParse.Parsed(UiTemplate(parts.toList()))
    }

    private fun take(end: Int) {
        buffer.append(text, index, end)
        index = end
    }

    private fun escape(open: Int) {
        buffer.append(text, index, open - 1).append(OPEN)
        index = open + OPEN.length
    }

    private fun bind(open: Int) {
        val close = text.indexOf(CLOSE, open + OPEN.length)
        val inner = if (close < 0) "" else text.substring(open + OPEN.length, close).trim()
        val path = if (close < 0) null else parseUiPath(inner)
        when {
            close < 0 -> error = "Unclosed {{"

            path == null -> error = "Invalid binding path '$inner'"

            else -> {
                take(open)
                flush()
                parts += UiTemplatePart.Binding(path)
                index = close + CLOSE.length
            }
        }
    }

    private fun flush() {
        if (buffer.isNotEmpty()) parts += UiTemplatePart.Text(buffer.toString())
        buffer.clear()
    }
}

/** Parses a bare path such as `item.labels[0]`, or returns null. */
internal fun parseUiPath(text: String): UiPath? {
    val match = PathPattern.matchEntire(text) ?: return null
    val root = UiPathRoot.of(match.groupValues[1]) ?: return null
    val segments = SegmentPattern.findAll(match.groupValues[2]).map { segment ->
        segment.groups[1]?.value?.let { UiPathSegment.Key(it) } ?: UiPathSegment.Index(segment.groupValues[2].toInt())
    }
    return UiPath(root, segments.toList())
}

private const val OPEN = "{{"
private const val CLOSE = "}}"
private const val ESCAPE = '\\'
private val PathPattern = Regex("""([A-Za-z_][A-Za-z0-9_]*)((?:\.[A-Za-z_][A-Za-z0-9_-]*|\[[0-9]{1,6}])*)""")
private val SegmentPattern = Regex("""\.([A-Za-z_][A-Za-z0-9_-]*)|\[([0-9]{1,6})]""")
