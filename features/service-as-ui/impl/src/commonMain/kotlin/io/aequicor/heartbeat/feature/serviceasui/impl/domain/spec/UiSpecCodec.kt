package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Bounds of the spec format beyond the library's byte limit. */
internal object UiSpecLimits {
    /** Nodes in the body and all modals together. */
    const val NODES: Int = 400

    /** Nesting of nodes. */
    const val DEPTH: Int = 16

    /** Nesting of raw JSON arrays and objects, checked before any parsing. */
    const val JSON_DEPTH: Int = 64

    /** Diagnostics reported for one spec. */
    const val DIAGNOSTICS: Int = 20

    /** Data sources of one spec. */
    const val SOURCES: Int = 8

    /** Named modals of one spec. */
    const val MODALS: Int = 8

    /** Actions of one sequence. */
    const val SEQUENCE: Int = 8

    /** Columns of one table. */
    const val COLUMNS: Int = 12

    /** Options of one select. */
    const val OPTIONS: Int = 64

    /** UTF-8 bytes of the initial state as JSON. */
    const val STATE_BYTES: Int = 16 * 1024

    /** UTF-8 bytes of the HTML of a web node. */
    const val HTML_BYTES: Int = 48 * 1024
}

/** What is wrong with a spec; [message] is shown to the agent and in the editor. */
internal enum class UiSpecProblem(val message: String) {
    /** Not JSON at all. */
    InvalidJson("not valid JSON"),

    /** Over [ServiceUiLimits.SPEC_BYTES]. */
    TooLarge("the spec is larger than ${ServiceUiLimits.SPEC_BYTES} bytes"),

    /** Over [UiSpecLimits.DEPTH] nested nodes or [UiSpecLimits.JSON_DEPTH] nested JSON values. */
    TooDeep("nodes are nested deeper than ${UiSpecLimits.DEPTH} levels"),

    /** Over [UiSpecLimits.NODES] nodes. */
    TooManyNodes("the spec has more than ${UiSpecLimits.NODES} nodes"),

    /** A version other than [UI_SPEC_VERSION]. */
    UnsupportedVersion("only version $UI_SPEC_VERSION is supported"),

    /** Unknown type, unknown or missing field, or a value of the wrong type. */
    Malformed("the structure does not match the spec format"),

    /** A `{{path}}` that does not parse, or a field that needs exactly one binding. */
    InvalidBinding("invalid binding"),

    /** `data.<source>`, `status.<source>` or `reload` name an undeclared data source. */
    UnknownSource("unknown data source"),

    /** A state key that the initial `state` does not declare. */
    UnknownStateKey("state key is not declared in the initial state"),

    /** `open` names an undeclared modal. */
    UnknownModal("unknown modal"),

    /** `open` names a surface the owner does not have. */
    UnknownSurface("unknown surface of this owner"),

    /** A method the owner does not publish (a warning while its methods are unknown). */
    UnknownMethod("the owner does not publish this method"),

    /** `item` or `index` outside list items and table cells. */
    OutOfScope("item and index exist only inside list items and table rows"),

    /** A web document outside panel and modal surfaces and modals. */
    PlacementForbidden("web documents are allowed only in panel and modal surfaces and in modals"),

    /** Methods, data sources, sending prompts, reveal or a web bridge in a card without an owner. */
    OriginForbidden("not allowed in a card without an owner"),

    /** Too many sources, modals, actions, columns, options or web nodes, or too large state or HTML. */
    LimitExceeded("exceeds a limit of the spec format"),

    /** A diagram source containing a Markdown fence. */
    DiagramFence("diagram sources must not contain ```"),

    /** A literal link that is not http or https. */
    InvalidUrl("only http and https links are allowed"),

    /** A value that is not allowed for this field. */
    InvalidValue("invalid value"),

    /** A name that is not a short identifier. */
    InvalidName("names start with a letter or '_' and contain letters, digits, '_' or '-', up to 32 characters"),
}

/**
 * A problem at the JSON [path] (`$.body.children[0]`); a warning does not reject the spec. [detail] may quote the
 * spec, so it goes to the agent and the editor only, never to logs.
 */
internal data class UiSpecDiagnostic(
    val path: String,
    val problem: UiSpecProblem,
    val detail: String? = null,
    val isWarning: Boolean = false,
) {
    /** Human-readable description for the agent and the editor. */
    fun describe(): String = buildString {
        if (isWarning) append("warning: ")
        append(path).append(": ").append(problem.message)
        detail?.let { append(" (").append(it).append(')') }
    }

    override fun toString(): String = "UiSpecDiagnostic(path=$path, problem=$problem, isWarning=$isWarning)"
}

/** Outcome of [decodeUiSpec]. */
internal sealed interface UiSpecDecoding {
    /** A structurally valid [spec]; references are checked separately. */
    data class Decoded(val spec: UiSpec) : UiSpecDecoding

    /** Not a spec, for [diagnostics]. */
    data class Rejected(val diagnostics: List<UiSpecDiagnostic>) : UiSpecDecoding
}

/**
 * Decodes the JSON [text] of a spec within the size, nesting and node limits. Pure and safe for untrusted input:
 * nesting is measured iteratively before the recursive decoder runs.
 */
internal fun decodeUiSpec(text: String): UiSpecDecoding {
    if (text.encodeToByteArray().size > ServiceUiLimits.SPEC_BYTES) return rejected(UiSpecProblem.TooLarge)
    if (jsonNesting(text) > UiSpecLimits.JSON_DEPTH) return rejected(UiSpecProblem.TooDeep)
    val tree = parsing { SpecJson.parseToJsonElement(text) }
    val element = tree.getOrNull() ?: return rejected(UiSpecProblem.InvalidJson, tree.exceptionOrNull()?.detail())
    return decodeTree(element)
}

private fun decodeTree(element: JsonElement): UiSpecDecoding {
    val decoded = parsing { SpecJson.decodeFromJsonElement(UiSpec.serializer(), element) }
    val spec = decoded.getOrNull()
    val error = decoded.exceptionOrNull()
    return when {
        spec == null -> rejected(UiSpecProblem.Malformed, error?.detail(), error?.path() ?: ROOT)
        spec.version != UI_SPEC_VERSION -> rejected(UiSpecProblem.UnsupportedVersion, path = "$ROOT.version")
        else -> checkStructure(spec)
    }
}

private fun checkStructure(spec: UiSpec): UiSpecDecoding {
    var nodes = 0
    var deepest = 0
    fun visit(node: UiNode, depth: Int) {
        nodes++
        deepest = maxOf(deepest, depth)
        if (nodes <= UiSpecLimits.NODES && depth <= UiSpecLimits.DEPTH) node.children.forEach { visit(it, depth + 1) }
    }
    spec.bodies.forEach { visit(it, 1) }
    return when {
        nodes > UiSpecLimits.NODES -> rejected(UiSpecProblem.TooManyNodes)
        deepest > UiSpecLimits.DEPTH -> rejected(UiSpecProblem.TooDeep)
        else -> UiSpecDecoding.Decoded(spec)
    }
}

/**
 * Deepest nesting of arrays and objects in JSON [text], counted character by character: the kotlinx tree reader and
 * decoders recurse per level, so deeper input must be refused before it reaches them. Strings are skipped.
 */
private fun jsonNesting(text: String): Int {
    var depth = 0
    var deepest = 0
    var isInString = false
    var isEscaped = false
    for (char in text) {
        when {
            isEscaped -> isEscaped = false

            isInString -> {
                isEscaped = char == '\\'
                isInString = char != '"'
            }

            char == '"' -> isInString = true

            char == '[' || char == '{' -> deepest = maxOf(deepest, ++depth)

            char == ']' || char == '}' -> depth--
        }
    }
    return deepest
}

/** Runs a kotlinx decoder; its failures describe the input and are returned to the caller as data. */
private inline fun <T> parsing(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: IllegalArgumentException) {
    Result.failure(e)
}

private fun rejected(problem: UiSpecProblem, detail: String? = null, path: String = ROOT): UiSpecDecoding =
    UiSpecDecoding.Rejected(listOf(UiSpecDiagnostic(path, problem, detail)))

private fun Throwable.detail(): String? =
    message?.lineSequence()?.firstOrNull()?.trim()?.take(MAX_DETAIL)?.takeIf { it.isNotEmpty() }

private fun Throwable.path(): String? =
    message?.let { PathInMessage.find(it)?.groupValues?.get(1)?.trimEnd('.', ',', ';') }

private const val ROOT = "$"
private const val MAX_DETAIL = 240
private val PathInMessage = Regex("""(?:at path|at element)[:\s]+(\$\S*)""")
private val SpecJson = Json {
    classDiscriminator = "type"
    explicitNulls = false
}
