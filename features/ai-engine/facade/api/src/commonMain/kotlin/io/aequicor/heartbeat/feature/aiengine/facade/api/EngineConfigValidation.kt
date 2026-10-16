package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A problem of the configuration at [path] of [document]; [isBlocking] problems prevent saving. */
public data class ConfigProblem(
    val document: ConfigDocumentId,
    val path: List<String>,
    val reason: ConfigProblemReason,
    val isBlocking: Boolean = true,
    /** The field that reported the problem, when one did. */
    val field: String? = null,
)

/** Why a configuration value is rejected or questionable. */
public enum class ConfigProblemReason {
    /** The schema declares no such document. */
    UnknownDocument,

    /** The document has another format than declared. */
    WrongFormat,

    /** The document or field does not apply in this scope. */
    NotInScope,

    /** The value or document is too long, or has too many items. */
    TooLong,

    /** The value has another JSON type than the field expects. */
    WrongType,

    /** The value is none of the offered choices. */
    NotAnOption,

    /** The number is outside the allowed range. */
    OutOfRange,

    /** The name or key does not match the expected pattern. */
    InvalidKey,

    /** A single-line value contains a line break or a control character. */
    ControlCharacter,

    /** The entry matches none of the declared variants. */
    UnknownVariant,

    /** Heartbeat sets this key itself; it is ignored at launch. */
    Owned,

    /** Project files are shared and never receive vault references. */
    SecretInProject,

    /** A vault reference points to a missing entry; the adapter leaves the value out. */
    MissingSecret,

    /** A credential is written as plain text; keep it in the vault instead. */
    SecretLiteral,
}

/**
 * Checks [values] for [scope] against [schema] without IO. Blocking problems prevent saving; warnings
 * ([ConfigProblem.isBlocking] false) are shown next to the value. Keys outside the declared fields are not checked.
 */
public fun validateEngineConfig(
    schema: EngineConfigSchema,
    values: EngineConfigValues,
    scope: ConfigScope,
): List<ConfigProblem> = buildList {
    values.documents.forEach { (id, document) ->
        val spec = schema.document(id)
        when {
            spec == null -> add(ConfigProblem(id, emptyList(), ConfigProblemReason.UnknownDocument))
            spec.format != document.format -> add(ConfigProblem(id, emptyList(), ConfigProblemReason.WrongFormat))
            scope !in spec.scopes -> add(ConfigProblem(id, emptyList(), ConfigProblemReason.NotInScope))
            document.length > spec.maxChars -> add(ConfigProblem(id, emptyList(), ConfigProblemReason.TooLong))
        }
    }
    schema.pages.forEach { page ->
        page.sections.forEach { section ->
            section.fields.forEach { field -> addAll(fieldProblems(field, values, scope)) }
        }
    }
    schema.ownedKeys.forEach { owned ->
        val document = values.documents[owned.document] as? ConfigDocument.Json
        if (document?.value?.at(owned.path) != null) {
            add(ConfigProblem(owned.document, owned.path, ConfigProblemReason.Owned, isBlocking = false))
        }
    }
    addAll(secretProblems(values, scope))
}

private val ConfigDocument.format: ConfigFormat
    get() = when (this) {
        is ConfigDocument.Json -> ConfigFormat.Json
        is ConfigDocument.Text -> ConfigFormat.Text
    }

private val ConfigDocument.length: Int
    get() = when (this) {
        is ConfigDocument.Json -> value.toString().length
        is ConfigDocument.Text -> value.length
    }

private fun fieldProblems(field: ConfigField, values: EngineConfigValues, scope: ConfigScope): List<ConfigProblem> {
    val value = values.read(field.binding) ?: return emptyList()
    val path = (field.binding as? ConfigBinding.JsonPath)?.path.orEmpty()
    val problem = { at: List<String>, reason: ConfigProblemReason ->
        ConfigProblem(field.binding.document, at, reason, field = field.id)
    }
    if (scope !in field.scopes) return listOf(problem(path, ConfigProblemReason.NotInScope))
    return valueProblems(field.kind, value, path).map { (at, reason) -> problem(at, reason) }
}

private fun valueProblems(
    kind: ConfigFieldKind,
    value: JsonElement,
    path: List<String>,
): List<Pair<List<String>, ConfigProblemReason>> {
    val reason: ConfigProblemReason? = when (kind) {
        is ConfigFieldKind.Flag -> ConfigProblemReason.WrongType.takeIf { value.booleanOrNull() == null }

        is ConfigFieldKind.Choice -> when {
            value !is JsonPrimitive -> ConfigProblemReason.WrongType
            kind.options.none { it.value == value } -> ConfigProblemReason.NotAnOption
            else -> null
        }

        is ConfigFieldKind.Number -> numberProblem(kind, value)

        is ConfigFieldKind.Text -> textProblem(value, kind.maxChars)

        is ConfigFieldKind.TextList -> return listProblems(kind, value, path)

        is ConfigFieldKind.KeyValues -> return keyValueProblems(kind, value, path)

        is ConfigFieldKind.Entries -> return entriesProblems(kind, value, path)

        is ConfigFieldKind.Document -> ConfigProblemReason.WrongType.takeIf { value.stringOrNull() == null }

        is ConfigFieldKind.ManagedNote -> null
    }
    return listOfNotNull(reason?.let { path to it })
}

private fun numberProblem(kind: ConfigFieldKind.Number, value: JsonElement): ConfigProblemReason? {
    val primitive = value.primitiveOrNull()
    val number = primitive?.takeUnless { it.isString }?.longOrNull ?: return ConfigProblemReason.WrongType
    val isBelow = kind.min?.let { number < it } == true
    val isAbove = kind.max?.let { number > it } == true
    return ConfigProblemReason.OutOfRange.takeIf { isBelow || isAbove }
}

private fun textProblem(value: JsonElement, maxChars: Int): ConfigProblemReason? {
    val text = value.stringOrNull() ?: return ConfigProblemReason.WrongType
    return when {
        text.length > maxChars -> ConfigProblemReason.TooLong
        text.any(Char::isControlCharacter) -> ConfigProblemReason.ControlCharacter
        else -> null
    }
}

private fun listProblems(
    kind: ConfigFieldKind.TextList,
    value: JsonElement,
    path: List<String>,
): List<Pair<List<String>, ConfigProblemReason>> {
    val items = value as? JsonArray ?: return listOf(path to ConfigProblemReason.WrongType)
    if (items.size > kind.maxItems) return listOf(path to ConfigProblemReason.TooLong)
    return items.mapIndexedNotNull { index, item ->
        textProblem(item, DEFAULT_MAX_VALUE_CHARS)?.let { path + index.toString() to it }
    }
}

private fun keyValueProblems(
    kind: ConfigFieldKind.KeyValues,
    value: JsonElement,
    path: List<String>,
): List<Pair<List<String>, ConfigProblemReason>> {
    val entries = value as? JsonObject ?: return listOf(path to ConfigProblemReason.WrongType)
    if (entries.size > kind.maxItems) return listOf(path to ConfigProblemReason.TooLong)
    val keyPattern = kind.keyPattern?.let(::Regex)
    return entries.mapNotNull { (key, item) ->
        val choices = kind.valueChoices
        val reason = when {
            keyPattern?.matches(key) == false -> ConfigProblemReason.InvalidKey
            choices != null && choices.none { it.value == item } -> ConfigProblemReason.NotAnOption
            else -> textProblem(item, DEFAULT_MAX_VALUE_CHARS)
        }
        reason?.let { path + key to it }
    }
}

private fun entriesProblems(
    kind: ConfigFieldKind.Entries,
    value: JsonElement,
    path: List<String>,
): List<Pair<List<String>, ConfigProblemReason>> {
    val items: List<Pair<String, JsonElement>> = when (kind.shape) {
        EntriesShape.NamedMap -> (value as? JsonObject)?.toList()
        EntriesShape.List -> (value as? JsonArray)?.mapIndexed { index, item -> index.toString() to item }
    } ?: return listOf(path to ConfigProblemReason.WrongType)
    if (items.size > kind.maxItems) return listOf(path to ConfigProblemReason.TooLong)
    val keyPattern = kind.keyPattern?.let(::Regex)
    return items.flatMap { (key, item) ->
        val itemPath = path + key
        val variant = kind.variants.firstOrNull { it.match.accepts(item) }
        when {
            kind.shape == EntriesShape.NamedMap && keyPattern?.matches(key) == false ->
                listOf(itemPath to ConfigProblemReason.InvalidKey)

            variant == null -> listOf(itemPath to ConfigProblemReason.UnknownVariant)

            else -> variant.fields.flatMap { field ->
                val fieldValue = if (field.path.isEmpty()) item else (item as? JsonObject)?.at(field.path)
                fieldValue?.let { valueProblems(field.kind, it, itemPath + field.path) }.orEmpty()
            }
        }
    }
}

/** Whether [item] has the form this match describes. */
public fun EntryMatch.accepts(item: JsonElement): Boolean = when (this) {
    EntryMatch.Primitive -> item.stringOrNull() != null
    EntryMatch.AnyObject -> item is JsonObject
    is EntryMatch.HasKey -> item is JsonObject && key in item
    is EntryMatch.KeyEquals -> item is JsonObject && item[key] == value
}

private fun secretProblems(values: EngineConfigValues, scope: ConfigScope): List<ConfigProblem> = buildList {
    values.documents.forEach { (id, document) ->
        if (document !is ConfigDocument.Json) return@forEach
        visitStrings(document.value, emptyList()) { path, text ->
            val slot = secretSlotOf(text)
            when {
                slot != null && scope == ConfigScope.Project ->
                    add(ConfigProblem(id, path, ConfigProblemReason.SecretInProject))

                slot != null && slot !in values.secrets ->
                    add(ConfigProblem(id, path, ConfigProblemReason.MissingSecret, isBlocking = false))

                slot == null && path.size >= 2 && isSecretKey(path.last()) && !isEnvironmentReference(text) ->
                    add(ConfigProblem(id, path, ConfigProblemReason.SecretLiteral, isBlocking = false))
            }
        }
    }
}

/** `${NAME}` or `$NAME`, or a whole-value `!command`: the value is resolved by the engine, not stored in clear text. */
private fun isEnvironmentReference(text: String): Boolean =
    text.isEmpty() || text.startsWith("!") || EnvironmentReference.matches(text)

private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun Char.isControlCharacter(): Boolean = this < ' ' || this == '\u007f'

private val EnvironmentReference = Regex("\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?")

private fun JsonElement.booleanOrNull(): Boolean? = primitiveOrNull()?.takeUnless { it.isString }?.booleanOrNull
