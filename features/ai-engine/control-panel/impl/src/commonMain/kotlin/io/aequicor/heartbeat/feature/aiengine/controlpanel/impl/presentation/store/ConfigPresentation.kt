package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListSyntax
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateEngineConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Every field of the schema, page by page. */
internal fun EngineConfigSchema.fields(): List<ConfigField> =
    pages.flatMap { page -> page.sections.flatMap { it.fields } }

/** The field [id], or null. */
internal fun EngineConfigSchema.field(id: String): ConfigField? = fields().firstOrNull { it.id == id }

/** The configuration of [engine] while engine configuration is on, or null. */
internal fun EngineControlState.configOf(engine: EngineId): EngineConfigState? =
    (this as? EngineControlState.Active)?.snapshot?.configuration?.takeIf { it.isEnabled }?.engine(engine)

/**
 * The profile form of this configuration with [draft] applied: the visible profile fields of every page with their
 * values, defaults and problems. A page without a visible field is left out.
 */
internal fun EngineConfigState.form(draft: ConfigDraft?): ConfigFormUi {
    val values = draft?.values ?: profile
    val typed = draft?.invalidNumbers.orEmpty()
    val visible = schema.fields().filter { it.isShown(values) }
    val problems = validateEngineConfig(schema, values, ConfigScope.Profile)
    val owned = problems.groupBy { it.owner(visible)?.id }
    val pages = schema.pages.map { page ->
        val sections = page.sections.mapNotNull { section ->
            val fields = section.fields.filter { it in visible }
            if (fields.isEmpty()) return@mapNotNull null
            val rows = fields.map { it.toUi(values, owned[it.id].orEmpty(), typed[it.id]) }
            ConfigSectionUi(section.id, section.title.toUi(), section.note?.toUi(), rows.toImmutableList())
        }
        ConfigPageUi(page.id, page.title.toUi(), sections.toImmutableList())
    }
    return ConfigFormUi(
        pages = pages.filter { it.sections.isNotEmpty() }.toImmutableList(),
        isDirty = draft != null && (draft.values != profile || typed.isNotEmpty()),
        blockingProblems = problems.count { it.isBlocking } + typed.size,
        hasConflict = draft != null && !draft.isSubmitted && draft.base != revision,
        isSubmitted = draft?.isSubmitted == true,
        otherProblems = owned[null].orEmpty().map { it.toUi(emptyList()) }.toImmutableList(),
    )
}

/**
 * [draft] (or a new one from the saved configuration) after [input] for the field [fieldId]. A number that does not
 * parse is kept as typed and blocks saving; an input that does not fit the field, an unknown field and edits of a
 * draft being saved change nothing.
 */
internal fun EngineConfigState.edit(draft: ConfigDraft?, fieldId: String, input: FieldInputUi): ConfigDraft? {
    val field = schema.field(fieldId)
    if (draft?.isSubmitted == true || field == null) return draft
    val current = draft ?: ConfigDraft(revision, profile)
    val typed = current.invalidNumbers - fieldId
    val unparsed = (input as? FieldInputUi.Number)?.text
        ?.takeIf { field.kind is ConfigFieldKind.Number && it.isNotBlank() && it.trim().toLongOrNull() == null }
    val written = input.toJson(field.kind)
    return when {
        unparsed != null -> current.copy(invalidNumbers = typed + (fieldId to unparsed))
        written == null -> current
        else -> current.copy(values = current.values.write(field.binding, written.value), invalidNumbers = typed)
    }
}

/** [values] as saved: blank list items are dropped and emptied lists removed. */
internal fun EngineConfigSchema.cleaned(values: EngineConfigValues): EngineConfigValues =
    fields().filter { it.kind is ConfigFieldKind.TextList }.fold(values) { result, field ->
        val items = result.read(field.binding) as? JsonArray ?: return@fold result
        val kept = items.filterNot { it.isBlankText() }
        val list = kept.takeIf { it.isNotEmpty() }?.let(::JsonArray)
        if (kept.size == items.size) result else result.write(field.binding, list)
    }

/**
 * The draft as the machine moved on: dropped with the configuration, kept while its save runs, editable again when
 * the save failed and dropped once the saved revision changed after a successful save.
 */
internal fun ConfigDraft?.settle(config: EngineConfigState?, state: EngineControlState.Active): ConfigDraft? = when {
    this == null || config == null -> null
    !isSubmitted || state.pending is ControlOperation.SaveProfile -> this
    state.failed?.operation is ControlOperation.SaveProfile -> copy(isSubmitted = false)
    config.revision != base -> null
    else -> this
}

private data class Written(val value: JsonElement?)

/** The JSON this input writes for a field of [kind]; null when it does not fit the kind. */
private fun FieldInputUi.toJson(kind: ConfigFieldKind): Written? = when (this) {
    FieldInputUi.Reset -> Written(null)

    is FieldInputUi.Flag -> Written(JsonPrimitive(value)).takeIf { kind is ConfigFieldKind.Flag }

    is FieldInputUi.Choice -> (kind as? ConfigFieldKind.Choice)?.options
        ?.firstOrNull { it.value.toString() == key }
        ?.let { Written(it.value) }

    is FieldInputUi.Number ->
        Written(text.trim().toLongOrNull()?.let(::JsonPrimitive)).takeIf { kind is ConfigFieldKind.Number }

    is FieldInputUi.Text -> Written(text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
        .takeIf { kind is ConfigFieldKind.Text || kind is ConfigFieldKind.Document }

    is FieldInputUi.TextList -> Written(items.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) })
        .takeIf { kind is ConfigFieldKind.TextList }
}

/** A profile field whose condition holds. */
private fun ConfigField.isShown(values: EngineConfigValues): Boolean {
    if (ConfigScope.Profile !in scopes) return false
    val condition = visibleWhen ?: return true
    val value = values.read(condition.binding) ?: return condition.isMetWhenAbsent
    return value == condition.value
}

/** The visible field a problem belongs to: the reporting field, else the deepest binding containing its path. */
private fun ConfigProblem.owner(fields: List<ConfigField>): ConfigField? {
    field?.let { id -> return fields.firstOrNull { it.id == id } }
    return fields.filter { it.binding.document == document && it.binding.contains(path) }
        .maxByOrNull { it.binding.depth }
}

private fun ConfigBinding.contains(path: List<String>): Boolean = when (this) {
    is ConfigBinding.JsonPath -> path.take(this.path.size) == this.path
    is ConfigBinding.WholeDocument -> true
}

private val ConfigBinding.depth: Int
    get() = (this as? ConfigBinding.JsonPath)?.path?.size ?: 0

private fun ConfigField.toUi(values: EngineConfigValues, problems: List<ConfigProblem>, typed: String?): ConfigFieldUi {
    val value = values.read(binding)
    val base = (binding as? ConfigBinding.JsonPath)?.path.orEmpty()
    val typing = listOfNotNull(typed?.let { ConfigProblemUi(ConfigProblemReasonUi.NotANumber) })
    return ConfigFieldUi(
        id = id,
        title = title.toUi(),
        description = description?.toUi(),
        editor = kind.editor(value, typed),
        isSet = value != null,
        isAdvanced = isAdvanced,
        problems = (problems.map { it.toUi(base) } + typing).toImmutableList(),
    )
}

private fun ConfigProblem.toUi(base: List<String>) =
    ConfigProblemUi(ConfigProblemReasonUi.valueOf(reason.name), path.drop(base.size).toImmutableList(), isBlocking)

private fun ConfigFieldKind.editor(value: JsonElement?, typed: String?): FieldEditorUi = when (this) {
    is ConfigFieldKind.Flag -> FieldEditorUi.Flag((value as? JsonPrimitive)?.booleanOrNull, isOnByDefault)

    is ConfigFieldKind.Choice -> FieldEditorUi.Choice(
        options.map { OptionUi(it.value.toString(), it.title.toUi()) }.toImmutableList(),
        selected = (value as? JsonPrimitive)?.toString(),
        default = default?.toString(),
    )

    is ConfigFieldKind.Number -> FieldEditorUi.Number(
        typed ?: value?.text().orEmpty(),
        default,
        min,
        max,
        unit?.let { UnitUi.valueOf(it.name) },
    )

    is ConfigFieldKind.Text -> FieldEditorUi.Text(value?.text().orEmpty(), TextRoleUi.valueOf(role.name), default)

    is ConfigFieldKind.TextList -> FieldEditorUi.TextList(
        (value as? JsonArray).orEmpty().map { it.text() }.toImmutableList(),
        TextRoleUi.valueOf(role.name),
        isPatterns = syntax == ListSyntax.ResourcePatterns,
    )

    is ConfigFieldKind.Document -> FieldEditorUi.Document(value?.text().orEmpty(), language)

    is ConfigFieldKind.ManagedNote -> FieldEditorUi.Managed(reason.toUi())

    is ConfigFieldKind.KeyValues, is ConfigFieldKind.Entries -> FieldEditorUi.Structured(value.count())
}

private fun JsonElement.isBlankText(): Boolean =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.isBlank() == true

/** The text of a string, or the JSON of anything else. */
private fun JsonElement.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()

private fun JsonElement?.count(): Int = when (this) {
    is JsonObject -> size
    is JsonArray -> size
    null -> 0
    else -> 1
}
