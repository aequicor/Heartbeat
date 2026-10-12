package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPath
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPathRoot
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPathSegment
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTemplate
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTemplateParse
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTemplatePart
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.parseUiTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Load status of one data source. */
internal sealed interface UiSourceStatus {
    /** Not loaded yet (manual sources before their first reload). */
    data object Idle : UiSourceStatus

    /** A call is running. */
    data object Loading : UiSourceStatus

    /** The last call returned a value. */
    data object Ready : UiSourceStatus

    /** The last call failed; [message] is shown to the user. */
    data class Failed(val message: String) : UiSourceStatus {
        override fun toString(): String = "Failed(***)"
    }
}

/** Facts about where the surface runs, readable as `context.*`. */
internal data class UiFacts(val platform: String = "desktop", val hasSession: Boolean = false)

/** The current list element and its position, readable as `item` and `index`. */
internal data class UiScope(val item: JsonElement? = null, val index: Int? = null) {
    override fun toString(): String = "UiScope(index=${index ?: "none"})"
}

/**
 * Everything bindings read: data source results, local state, the arguments the surface or modal was opened with,
 * source statuses, context facts and the buttons whose action is running.
 */
internal data class UiValues(
    val data: Map<String, JsonElement> = emptyMap(),
    val state: Map<String, JsonElement> = emptyMap(),
    val args: JsonObject = JsonObject(emptyMap()),
    val status: Map<String, UiSourceStatus> = emptyMap(),
    val facts: UiFacts = UiFacts(),
    val busy: Set<String> = emptySet(),
) {
    override fun toString(): String = "UiValues(data=${data.keys}, state=${state.keys}, busy=${busy.size})"
}

/** Resolves bindings in [values]; [templates] caches parsed spec strings across evaluations of one surface. */
internal class UiBindings(
    private val values: UiValues,
    private val templates: MutableMap<String, UiTemplate?> = HashMap(),
) {
    /** The value at [path] in [scope], or null when it does not exist. */
    @HighFrequency
    fun resolve(path: UiPath, scope: UiScope): JsonElement? {
        val root = when (path.root) {
            UiPathRoot.Data -> JsonObject(values.data)

            UiPathRoot.State -> JsonObject(values.state)

            UiPathRoot.Args -> values.args

            UiPathRoot.Item -> scope.item

            UiPathRoot.Index -> scope.index?.let { JsonPrimitive(it) }

            UiPathRoot.Status -> JsonObject(values.status.mapValues { it.value.json() })

            UiPathRoot.Context -> JsonObject(
                mapOf(
                    "platform" to JsonPrimitive(values.facts.platform),
                    "hasSession" to JsonPrimitive(values.facts.hasSession),
                ),
            )
        }
        return path.segments.fold(root) { element, segment ->
            when (segment) {
                is UiPathSegment.Key -> (element as? JsonObject)?.get(segment.name)
                is UiPathSegment.Index -> (element as? JsonArray)?.getOrNull(segment.index)
            }
        }
    }

    /** [text] with every binding replaced by its value as text. */
    @HighFrequency
    fun text(text: String, scope: UiScope): String {
        val template = template(text) ?: return text
        return template.parts.joinToString("") { part ->
            when (part) {
                is UiTemplatePart.Text -> part.value
                is UiTemplatePart.Binding -> resolve(part.path, scope).text()
            }
        }
    }

    /** The JSON value of [text]: a whole-string binding keeps its type, anything else is text. */
    @HighFrequency
    fun value(text: String, scope: UiScope): JsonElement {
        val single = template(text)?.single
        return if (single != null) resolve(single, scope) ?: JsonNull else JsonPrimitive(text(text, scope))
    }

    /** [element] with every string evaluated as a template; objects and arrays are walked. */
    @HighFrequency
    fun json(element: JsonElement, scope: UiScope): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { json(it.value, scope) })
        is JsonArray -> JsonArray(element.map { json(it, scope) })
        is JsonPrimitive -> if (element.isString) value(element.content, scope) else element
    }

    /** Whether the binding [text] is truthy. */
    @HighFrequency
    fun isTrue(text: String, scope: UiScope): Boolean = value(text, scope).isTruthy()

    private fun template(text: String): UiTemplate? = templates.getOrPut(text) {
        (parseUiTemplate(text) as? UiTemplateParse.Parsed)?.template
    }
}

/** Text of a value: strings and numbers as they are, null as empty, objects and arrays as JSON. */
internal fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    is JsonObject, is JsonArray -> toString()
}

/** False for null, false, 0, empty strings and empty collections. */
internal fun JsonElement?.isTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonObject -> isNotEmpty()
    is JsonArray -> isNotEmpty()
    is JsonPrimitive -> if (isString) content.isNotEmpty() else booleanOrNull ?: ((doubleOrNull ?: 0.0) != 0.0)
}

private fun UiSourceStatus.json(): JsonObject = JsonObject(
    mapOf(
        "loading" to JsonPrimitive(this == UiSourceStatus.Loading),
        "ready" to JsonPrimitive(this == UiSourceStatus.Ready),
        "failed" to JsonPrimitive(this is UiSourceStatus.Failed),
        "error" to ((this as? UiSourceStatus.Failed)?.message?.let { JsonPrimitive(it) } ?: JsonNull),
    ),
)
