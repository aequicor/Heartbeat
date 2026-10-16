package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntriesShape
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListSyntax
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.aiengine.facade.api.at
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlotOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The editor of a value of this kind. [typed] is a number as typed when it does not parse; [problems] are relative
 * to the value and are spread over the entries of an entries field; an [inherited] value (a project's profile
 * value) is the default the value falls back to.
 */
internal fun ConfigFieldKind.editor(
    value: JsonElement?,
    typed: String?,
    secrets: SecretView,
    problems: List<ConfigProblemUi>,
    inherited: JsonElement? = null,
): FieldEditorUi {
    val fallback = inherited as? JsonPrimitive
    return when (this) {
        is ConfigFieldKind.Flag ->
            FieldEditorUi.Flag((value as? JsonPrimitive)?.booleanOrNull, fallback?.booleanOrNull ?: isOnByDefault)

        is ConfigFieldKind.Choice -> FieldEditorUi.Choice(
            options.map { OptionUi(it.value.toString(), it.title.toUi()) }.toImmutableList(),
            selected = (value as? JsonPrimitive)?.toString(),
            default = fallback?.toString() ?: default?.toString(),
        )

        is ConfigFieldKind.Number -> FieldEditorUi.Number(
            typed ?: value?.text().orEmpty(),
            fallback?.longOrNull ?: default,
            min,
            max,
            unit?.let { UnitUi.valueOf(it.name) },
        )

        is ConfigFieldKind.Text ->
            FieldEditorUi.Text(value?.text().orEmpty(), TextRoleUi.valueOf(role.name), fallback?.text() ?: default)

        is ConfigFieldKind.TextList, is ConfigFieldKind.KeyValues, is ConfigFieldKind.Entries,
        is ConfigFieldKind.Document, is ConfigFieldKind.ManagedNote,
        -> structuredEditor(value, secrets, problems)
    }
}

private fun ConfigFieldKind.structuredEditor(
    value: JsonElement?,
    secrets: SecretView,
    problems: List<ConfigProblemUi>,
): FieldEditorUi = when (this) {
    is ConfigFieldKind.TextList -> FieldEditorUi.TextList(
        (value as? JsonArray).orEmpty().map { it.text() }.toImmutableList(),
        TextRoleUi.valueOf(role.name),
        isPatterns = syntax == ListSyntax.ResourcePatterns,
    )

    is ConfigFieldKind.Document -> FieldEditorUi.Document(value?.text().orEmpty(), language)

    is ConfigFieldKind.ManagedNote -> FieldEditorUi.Managed(reason.toUi())

    is ConfigFieldKind.KeyValues -> FieldEditorUi.KeyValues(
        (value as? JsonObject).orEmpty().map { (key, item) -> row(key, item.text(), secrets) }.toImmutableList(),
        isSecretAllowed && secrets.isAllowed,
        valueChoices?.map { OptionUi(it.value.toString(), it.title.toUi()) }?.toImmutableList(),
    )

    is ConfigFieldKind.Entries -> FieldEditorUi.Entries(
        itemTitle.toUi(),
        isNamed = shape == EntriesShape.NamedMap,
        variants = variants.map { OptionUi(it.id, it.title.toUi()) }.toImmutableList(),
        items = items(value).map { (key, item) -> entry(key, item, secrets, problems) }.toImmutableList(),
    )

    is ConfigFieldKind.Flag, is ConfigFieldKind.Choice, is ConfigFieldKind.Number, is ConfigFieldKind.Text ->
        editor(value, null, secrets, problems)
}

/** The items of an entries value with their keys: names of a map, positions of a list. */
internal fun ConfigFieldKind.Entries.items(value: JsonElement?): List<Pair<String, JsonElement>> = when (shape) {
    EntriesShape.NamedMap -> (value as? JsonObject)?.toList()
    EntriesShape.List -> (value as? JsonArray)?.mapIndexed { index, item -> index.toString() to item }
}.orEmpty()

/** The value of [field] inside [item]; a primitive item is its only field. */
internal fun EntryField.valueIn(item: JsonElement): JsonElement? =
    if (path.isEmpty()) item else (item as? JsonObject)?.at(path)

/** The text of a string, or the JSON of anything else. */
internal fun JsonElement.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()

private fun row(key: String, value: String, secrets: SecretView): KeyValueRowUi {
    val slot = secretSlotOf(value) ?: return KeyValueRowUi(key, value)
    val typed = secrets.typed[slot]
    val state = when {
        typed != null -> SecretUi.Typed
        slot in secrets.saved -> SecretUi.Saved
        else -> SecretUi.Missing
    }
    return KeyValueRowUi(key, typed.orEmpty(), slot, state)
}

private fun ConfigFieldKind.Entries.entry(
    key: String,
    item: JsonElement,
    secrets: SecretView,
    problems: List<ConfigProblemUi>,
): EntryUi {
    val own = problems.filter { it.path.firstOrNull() == key }.map { it.copy(path = it.path.drop(1).toImmutableList()) }
    val variant = variants.firstOrNull { it.match.accepts(item) }
        ?: return EntryUi(key, null, emptyList<ConfigFieldUi>().toImmutableList(), own.toImmutableList())
    val owner = { problem: ConfigProblemUi ->
        variant.fields.filter { problem.path.take(it.path.size) == it.path }.maxByOrNull { it.path.size }
    }
    val fields = variant.fields.map { field ->
        val value = field.valueIn(item)
        val fieldProblems = own.filter { owner(it) == field }
            .map { it.copy(path = it.path.drop(field.path.size).toImmutableList()) }
        ConfigFieldUi(
            id = field.id,
            title = field.title.toUi(),
            description = field.description?.toUi(),
            editor = field.kind.editor(value, null, secrets, fieldProblems),
            isSet = value != null,
            isAdvanced = field.isAdvanced,
            problems = fieldProblems.toImmutableList(),
        )
    }
    return EntryUi(key, variant.id, fields.toImmutableList(), own.filter { owner(it) == null }.toImmutableList())
}
