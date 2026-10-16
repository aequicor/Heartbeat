package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntriesShape
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryField
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryMatch
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryVariant
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlotOf
import io.aequicor.heartbeat.feature.aiengine.facade.api.with
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A changed value and what it does to the values typed for vault slots: a slot maps to the typed text, or to null
 * when what was typed for it is dropped. Never printed.
 */
internal data class Change(val value: JsonElement?, val secrets: Map<String, String?> = emptyMap()) {
    override fun toString(): String = "Change(secrets=${secrets.size})"
}

/** Names a new vault slot after [hint], unlike any of [taken]; [isAllowed] where values may move to the vault. */
internal data class SlotNames(val hint: String, val taken: Set<String>, val isAllowed: Boolean = true) {
    /** A slot for the value of [key]. */
    fun next(key: String): String {
        val base = "${hint}_$key".uppercase().map { if (it in 'A'..'Z' || it in '0'..'9') it else '_' }
            .joinToString("").trim('_').take(MAX_BASE).ifEmpty { "SECRET" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
    }

    /** Names for the fields of the entry [key]. */
    fun inside(key: String, field: String): SlotNames = copy(hint = "${hint}_${key}_$field")

    private companion object {
        const val MAX_BASE = 40
    }
}

/** This value of [kind] after [input]; null when the input does not fit the kind or changes nothing. */
internal fun ConfigFieldKind.change(current: JsonElement?, input: FieldInputUi, slots: SlotNames): Change? = when {
    input == FieldInputUi.Reset -> Change(null)
    this is ConfigFieldKind.Entries -> entriesChange(current, input, slots)
    this is ConfigFieldKind.KeyValues -> keyValuesChange(current, input, slots)
    else -> basicValue(input)?.let { Change(it.value) }
}

private data class Written(val value: JsonElement?)

/** The JSON a basic input writes for a value of this kind; null when it does not fit the kind. */
private fun ConfigFieldKind.basicValue(input: FieldInputUi): Written? = when (input) {
    is FieldInputUi.Flag -> Written(JsonPrimitive(input.isOn)).takeIf { this is ConfigFieldKind.Flag }

    is FieldInputUi.Choice -> (this as? ConfigFieldKind.Choice)?.options
        ?.firstOrNull { it.value.toString() == input.key }
        ?.let { Written(it.value) }

    is FieldInputUi.Number ->
        Written(input.text.trim().toLongOrNull()?.let(::JsonPrimitive)).takeIf { this is ConfigFieldKind.Number }

    is FieldInputUi.Text -> Written(input.text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
        .takeIf { this is ConfigFieldKind.Text || this is ConfigFieldKind.Document }

    is FieldInputUi.TextList -> Written(
        input.items.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) },
    )
        .takeIf { this is ConfigFieldKind.TextList }

    FieldInputUi.Reset, is FieldInputUi.KeyValues, is FieldInputUi.SetSecret, is FieldInputUi.AddEntry,
    is FieldInputUi.RemoveEntry, is FieldInputUi.RenameEntry, is FieldInputUi.EditEntry,
    -> null
}

private fun ConfigFieldKind.KeyValues.keyValuesChange(
    current: JsonElement?,
    input: FieldInputUi,
    slots: SlotNames,
): Change? = when (input) {
    is FieldInputUi.KeyValues -> {
        val pairs = input.rows.associate { row ->
            row.key to JsonPrimitive(
                row.slot?.let(::secretReference) ?: row.value,
            )
        }
        val typed = input.rows.mapNotNull { row ->
            val slot = row.slot ?: return@mapNotNull null
            slot to row.value.ifEmpty { null }
        }.toMap()
        Change(pairs.takeIf { it.isNotEmpty() }?.let(::JsonObject), typed)
    }

    is FieldInputUi.SetSecret -> (current as? JsonObject)?.let { pairs -> secretChange(pairs, input, slots) }

    is FieldInputUi.Flag, is FieldInputUi.Choice, is FieldInputUi.Number, is FieldInputUi.Text,
    is FieldInputUi.TextList, FieldInputUi.Reset, is FieldInputUi.AddEntry, is FieldInputUi.RemoveEntry,
    is FieldInputUi.RenameEntry, is FieldInputUi.EditEntry,
    -> null
}

/** Moves a value into the vault (what it held is typed for the new slot) or back into the file, empty. */
private fun ConfigFieldKind.KeyValues.secretChange(
    pairs: JsonObject,
    input: FieldInputUi.SetSecret,
    slots: SlotNames,
): Change? {
    val value = (pairs[input.key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val slot = secretSlotOf(value)
    return when {
        input.isSecret && slot == null && isSecretAllowed && slots.isAllowed -> {
            val new = slots.next(input.key)
            Change(
                JsonObject(pairs + (input.key to JsonPrimitive(secretReference(new)))),
                mapOf(new to value.ifEmpty { null }),
            )
        }

        !input.isSecret && slot != null -> Change(
            JsonObject(pairs + (input.key to JsonPrimitive(""))),
            mapOf(slot to null),
        )

        else -> null
    }
}

private fun ConfigFieldKind.Entries.entriesChange(
    current: JsonElement?,
    input: FieldInputUi,
    slots: SlotNames,
): Change? {
    val items = items(current)
    return when (input) {
        is FieldInputUi.AddEntry -> variants.firstOrNull { it.id == input.variant }?.let { variant ->
            val names = items.map { it.first }
            val key = if (shape == EntriesShape.NamedMap) freeName(variant.id, names) else "${items.size}"
            Change(build(items + (key to variant.template())))
        }

        is FieldInputUi.RemoveEntry -> Change(build(items.filterNot { it.first == input.key }))
            .takeIf { items.any { item -> item.first == input.key } }

        is FieldInputUi.RenameEntry -> Change(
            build(items.map { item -> if (item.first == input.key) input.name to item.second else item }),
        )
            .takeIf { shape == EntriesShape.NamedMap && items.none { item -> item.first == input.name } }

        is FieldInputUi.EditEntry -> editEntry(items, input, slots)

        is FieldInputUi.Flag, is FieldInputUi.Choice, is FieldInputUi.Number, is FieldInputUi.Text,
        is FieldInputUi.TextList, FieldInputUi.Reset, is FieldInputUi.KeyValues, is FieldInputUi.SetSecret,
        -> null
    }
}

private fun ConfigFieldKind.Entries.editEntry(
    items: List<Pair<String, JsonElement>>,
    input: FieldInputUi.EditEntry,
    slots: SlotNames,
): Change? {
    val index = items.indexOfFirst { it.first == input.key }
    val item = items.getOrNull(index)?.second ?: return null
    val variant = variants.firstOrNull { it.match.accepts(item) } ?: return null
    val field = variant.fields.firstOrNull { it.id == input.field } ?: return null
    val change = field.kind.change(field.valueIn(item), input.input, slots.inside(input.key, field.id)) ?: return null
    val updated = items.toMutableList().apply { set(index, input.key to variant.put(item, field, change.value)) }
    return Change(build(updated), change.secrets)
}

/** [item] with [value] in [field]; the key that identifies the variant stays, empty, so the entry keeps its form. */
private fun EntryVariant.put(item: JsonElement, field: EntryField, value: JsonElement?): JsonElement {
    if (field.path.isEmpty()) return value ?: JsonPrimitive("")
    val identity = (match as? EntryMatch.HasKey)?.key
    val kept = value ?: JsonPrimitive("").takeIf { field.path == listOfNotNull(identity) }
    return ((item as? JsonObject) ?: JsonObject(emptyMap())).with(field.path, kept)
}

private fun EntryVariant.template(): JsonElement = when (val match = match) {
    EntryMatch.Primitive -> JsonPrimitive("")
    EntryMatch.AnyObject -> JsonObject(emptyMap())
    is EntryMatch.HasKey -> JsonObject(mapOf(match.key to JsonPrimitive("")))
    is EntryMatch.KeyEquals -> JsonObject(mapOf(match.key to match.value))
}

private fun ConfigFieldKind.Entries.build(items: List<Pair<String, JsonElement>>): JsonElement? = when {
    items.isEmpty() -> null
    shape == EntriesShape.NamedMap -> JsonObject(items.toMap())
    else -> JsonArray(items.map { it.second })
}

private fun freeName(base: String, taken: List<String>): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
