package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntriesShape
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlots
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateEngineConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.with
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * values, defaults and problems. Problems are those of the values as they would be saved, so rows being typed do
 * not block saving. A page without a visible field is left out.
 */
internal fun EngineConfigState.form(draft: ConfigDraft?): ConfigFormUi {
    val values = draft?.values ?: profile
    val typed = draft?.invalidNumbers.orEmpty()
    val secrets = SecretView(values.secrets.keys, draft?.secrets.orEmpty())
    val visible = schema.fields().filter { it.isShown(values) }
    val saved = schema.cleaned(values).let { it.copy(secrets = it.secrets + secrets.typed.mapValues { PENDING }) }
    val problems = validateEngineConfig(schema, saved, ConfigScope.Profile)
    val owned = problems.groupBy { it.owner(visible)?.id }
    val pages = schema.pages.map { page ->
        val sections = page.sections.mapNotNull { section ->
            val fields = section.fields.filter { it in visible }
            if (fields.isEmpty()) return@mapNotNull null
            val rows = fields.map { it.toUi(values, owned[it.id].orEmpty(), typed[it.id], secrets) }
            ConfigSectionUi(section.id, section.title.toUi(), section.note?.toUi(), rows.toImmutableList())
        }
        ConfigPageUi(page.id, page.title.toUi(), sections.toImmutableList())
    }
    val files = schema.files(values, draft?.files.orEmpty())
    val invalidFiles = files.count { it.isInvalid }
    val isChanged = draft != null && (draft.values != profile || draft.secrets.isNotEmpty())
    return ConfigFormUi(
        pages = pages.filter { it.sections.isNotEmpty() }.toImmutableList(),
        isDirty = isChanged || typed.isNotEmpty() || invalidFiles > 0,
        blockingProblems = problems.count { it.isBlocking } + typed.size + invalidFiles,
        hasConflict = draft != null && !draft.isSubmitted && draft.base != revision,
        isSubmitted = draft?.isSubmitted == true,
        otherProblems = owned[null].orEmpty().map { it.toUi(emptyList()) }.toImmutableList(),
        files = files.toImmutableList(),
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
    val slots = SlotNames(field.id, current.values.secretSlots() + current.secrets.keys + profile.secrets.keys)
    val change = field.kind.change(current.values.read(field.binding), input, slots)
    return when {
        unparsed != null -> current.copy(invalidNumbers = typed + (fieldId to unparsed))

        change == null -> current

        else -> current.copy(
            values = current.values.write(field.binding, change.value),
            invalidNumbers = typed,
            // The file editor of the changed document shows it anew.
            files = current.files - field.binding.document.value,
            secrets = current.secrets + change.secrets.mapNotNull { (slot, text) -> text?.let { slot to it } } -
                change.secrets.filterValues { it == null }.keys,
        )
    }
}

/** The profile save of [draft]: its values as saved and the values typed for the slots they still use. */
internal fun EngineConfigSchema.saveOf(draft: ConfigDraft): ConfigEdit {
    val values = cleaned(draft.values)
    val used = values.secretSlots()
    return ConfigEdit(values, draft.secrets.filterKeys { it in used }.mapValues { Secret(it.value.toCharArray()) })
}

/** [values] as saved: blank list items and blank names are dropped and emptied values removed. */
internal fun EngineConfigSchema.cleaned(values: EngineConfigValues): EngineConfigValues =
    fields().fold(values) { result, field ->
        val value = result.read(field.binding) ?: return@fold result
        val clean = field.kind.clean(value)
        if (clean == value) result else result.write(field.binding, clean)
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

/** Revision standing in for a vault value that is typed but not saved yet. */
private const val PENDING = -1L

/** This value without blank list items and blank names, inside entries too; null when nothing is left. */
private fun ConfigFieldKind.clean(value: JsonElement): JsonElement? = when (this) {
    is ConfigFieldKind.TextList -> (value as? JsonArray)?.filterNot { it.isBlankText() }
        ?.let { kept -> JsonArray(kept).takeIf { kept.isNotEmpty() } } ?: value.takeUnless { it is JsonArray }

    is ConfigFieldKind.KeyValues -> (value as? JsonObject)?.filterKeys { it.isNotBlank() }
        ?.let { kept -> JsonObject(kept).takeIf { kept.isNotEmpty() } } ?: value.takeUnless { it is JsonObject }

    is ConfigFieldKind.Entries -> cleanEntries(value)

    else -> value
}

private fun ConfigFieldKind.Entries.cleanEntries(value: JsonElement): JsonElement {
    val items = items(value).map { (key, item) ->
        val variant = variants.firstOrNull { it.match.accepts(item) } ?: return@map key to item
        key to variant.fields.fold(item) { result, field ->
            val inner = field.valueIn(result) ?: return@fold result
            val clean = field.kind.clean(inner)
            when {
                clean == inner -> result
                field.path.isEmpty() -> clean ?: JsonPrimitive("")
                else -> (result as? JsonObject)?.with(field.path, clean) ?: result
            }
        }
    }
    return if (shape == EntriesShape.NamedMap) JsonObject(items.toMap()) else JsonArray(items.map { it.second })
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

private fun ConfigField.toUi(
    values: EngineConfigValues,
    problems: List<ConfigProblem>,
    typed: String?,
    secrets: SecretView,
): ConfigFieldUi {
    val value = values.read(binding)
    val base = (binding as? ConfigBinding.JsonPath)?.path.orEmpty()
    val relative = problems.map { it.toUi(base) }
    val typing = listOfNotNull(typed?.let { ConfigProblemUi(ConfigProblemReasonUi.NotANumber) })
    val editor = kind.editor(value, typed, secrets, relative)
    // Problems inside entries are shown at their entry; the field keeps those about the whole value.
    val own = if (editor is FieldEditorUi.Entries) relative.filter { it.path.isEmpty() } else relative
    return ConfigFieldUi(
        id = id,
        title = title.toUi(),
        description = description?.toUi(),
        editor = editor,
        isSet = value != null,
        isAdvanced = isAdvanced,
        problems = (own + typing).toImmutableList(),
    )
}

private fun ConfigProblem.toUi(base: List<String>) =
    ConfigProblemUi(ConfigProblemReasonUi.valueOf(reason.name), path.drop(base.size).toImmutableList(), isBlocking)

private fun JsonElement.isBlankText(): Boolean =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.isBlank() == true
