package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Content of one configuration document. */
public sealed interface ConfigDocument {
    /** A JSON object; unknown keys are kept as they are. */
    public data class Json(val value: JsonObject) : ConfigDocument {
        override fun toString(): String = "Json(keys=${value.size})"
    }

    /** Free text. */
    public data class Text(val value: String) : ConfigDocument {
        override fun toString(): String = "Text(chars=${value.length})"
    }
}

/**
 * Configuration of an engine in one scope: documents by id and the revisions of the vault entries their secret
 * references point to, so a replaced secret changes the value. Values may name the user's paths and servers and are
 * never logged.
 */
public data class EngineConfigValues(
    val documents: Map<ConfigDocumentId, ConfigDocument> = emptyMap(),
    /** Vault slot → revision of its current value. */
    val secrets: Map<String, Long> = emptyMap(),
) {
    /** Nothing is configured: the engine runs with its defaults. */
    val isEmpty: Boolean get() = documents.isEmpty()

    override fun toString(): String = "EngineConfigValues(documents=${documents.keys.map { it.value }})"
}

/** The document [id], or null when absent. */
public fun EngineConfigValues.document(id: ConfigDocumentId): ConfigDocument? = documents[id]

/** These values with [document] stored as [id]; null or an empty document removes it. */
public fun EngineConfigValues.withDocument(id: ConfigDocumentId, document: ConfigDocument?): EngineConfigValues {
    val isEmpty = when (document) {
        null -> true
        is ConfigDocument.Json -> document.value.isEmpty()
        is ConfigDocument.Text -> document.value.isEmpty()
    }
    return copy(documents = if (isEmpty) documents - id else documents + (id to requireNotNull(document)))
}

/**
 * The value at [binding]: the JSON element at a path, or the text of a whole document as a string. Null when absent
 * or when the document has another format.
 */
public fun EngineConfigValues.read(binding: ConfigBinding): JsonElement? = when (binding) {
    is ConfigBinding.WholeDocument -> (documents[binding.document] as? ConfigDocument.Text)?.let {
        JsonPrimitive(
            it.value,
        )
    }

    is ConfigBinding.JsonPath -> (documents[binding.document] as? ConfigDocument.Json)?.value?.at(binding.path)
}

/**
 * These values with [value] at [binding]. Null (or JSON null) removes the value and prunes emptied parent objects;
 * a JSON path replaces non-object intermediates. A whole document takes a string.
 */
public fun EngineConfigValues.write(binding: ConfigBinding, value: JsonElement?): EngineConfigValues {
    val element = value.takeUnless { it is JsonNull }
    return when (binding) {
        is ConfigBinding.WholeDocument -> {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(element == null || text != null) { "A whole document takes a string" }
            withDocument(binding.document, text?.let(ConfigDocument::Text))
        }

        is ConfigBinding.JsonPath -> {
            val current = (documents[binding.document] as? ConfigDocument.Json)?.value ?: JsonObject(emptyMap())
            withDocument(binding.document, ConfigDocument.Json(current.with(binding.path, element)))
        }
    }
}

/** The element at [path] of this object, or null. */
public fun JsonObject.at(path: List<String>): JsonElement? {
    var current: JsonElement = this
    for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
    return current
}

/** This object with [value] at [path]; null removes it and prunes emptied parents. */
public fun JsonObject.with(path: List<String>, value: JsonElement?): JsonObject {
    require(path.isNotEmpty()) { "Empty path" }
    val key = path.first()
    val updated = if (path.size == 1) {
        value
    } else {
        val child = this[key] as? JsonObject ?: JsonObject(emptyMap())
        child.with(path.drop(1), value).takeIf { it.isNotEmpty() }
    }
    return JsonObject(if (updated == null) this - key else this + (key to updated))
}

/** Placeholder written in place of a secret value; the adapter substitutes the vault entry at launch. */
public fun secretReference(slot: String): String {
    require(SecretSlot.matches(slot)) { "Invalid secret slot" }
    return "\${$SECRET_PREFIX$slot}"
}

/** The vault slot when [value] is exactly a [secretReference], otherwise null. */
public fun secretSlotOf(value: String): String? = SecretReference.matchEntire(value)?.groupValues?.get(1)

/** Vault slots referenced anywhere in the JSON documents of these values. */
public fun EngineConfigValues.secretSlots(): Set<String> = buildSet {
    documents.values.filterIsInstance<ConfigDocument.Json>().forEach { collectSlots(it.value, this) }
}

/** Whether [name] (a header or variable) usually carries a credential and should be kept in the vault. */
public fun isSecretKey(name: String): Boolean =
    isSecretVariable(name.replace('-', '_')) || name.lowercase() in SecretHeaders

private fun collectSlots(element: JsonElement, into: MutableSet<String>) {
    visitStrings(element, emptyList()) { _, text -> secretSlotOf(text)?.let(into::add) }
}

/** Visits every string value of [element] with its path. */
internal fun visitStrings(element: JsonElement, path: List<String>, visit: (List<String>, String) -> Unit) {
    when (element) {
        is JsonObject -> element.forEach { (key, child) -> visitStrings(child, path + key, visit) }
        is JsonArray -> element.forEachIndexed { index, child -> visitStrings(child, path + index.toString(), visit) }
        is JsonPrimitive -> if (element.isString) visit(path, element.content)
    }
}

private const val SECRET_PREFIX = "HEARTBEAT_SECRET_"
private val SecretSlot = Regex("[A-Z0-9_]{1,48}")
private val SecretReference = Regex("\\$\\{$SECRET_PREFIX([A-Z0-9_]{1,48})}")
private val SecretHeaders = setOf("authorization", "proxy-authorization", "cookie", "x-api-key", "api-key")
