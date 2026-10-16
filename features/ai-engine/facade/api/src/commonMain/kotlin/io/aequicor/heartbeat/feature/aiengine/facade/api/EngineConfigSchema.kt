package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonPrimitive

/**
 * Text an adapter supplies to the engine configuration UI: [default] in English and translations keyed by an ISO
 * 639-1 language code. Like [EngineDescriptor.title] it is data of the engine, not a screen resource.
 */
public data class LocalizedText(val default: String, val byLanguage: Map<String, String> = emptyMap()) {
    init {
        require(default.isNotBlank()) { "Empty LocalizedText" }
    }

    /** The translation for [language], falling back to [default]. */
    public fun resolve(language: String): String = byLanguage[language] ?: default
}

/** Identity of one configuration document of an engine (`settings`, `mcp`). */
public data class ConfigDocumentId(val value: String) {
    init {
        require(DocumentId.matches(value)) { "Invalid ConfigDocumentId" }
    }
}

/** How a configuration document is stored and edited. */
public enum class ConfigFormat {
    /** A JSON object; fields bind to paths inside it. */
    Json,

    /** Free text such as Markdown instructions. */
    Text,
}

/** Where a configuration applies. */
public enum class ConfigScope {
    /** The profile's own settings, written into the engine's configuration directory at launch. */
    Profile,

    /** Files inside a saved project, read and written in place. */
    Project,
}

/**
 * One document of an engine's configuration. [fileName] is what the user recognizes (`settings.json`); the adapter
 * owns the real location. A [isHostOnly] document is interpreted by the adapter itself (launch flags, environment)
 * and never written to the engine's own files.
 */
public data class ConfigDocumentSpec(
    val id: ConfigDocumentId,
    val title: LocalizedText,
    val format: ConfigFormat,
    val scopes: Set<ConfigScope>,
    val fileName: String,
    val maxChars: Int = DEFAULT_MAX_DOCUMENT_CHARS,
    val isHostOnly: Boolean = false,
) {
    init {
        require(scopes.isNotEmpty()) { "A document needs a scope" }
        require(fileName.isNotBlank()) { "Empty file name" }
        require(maxChars in 1..MAX_DOCUMENT_CHARS) { "Invalid document limit" }
        require(!isHostOnly || ConfigScope.Project !in scopes) { "Host-only documents are profile settings" }
    }
}

/** Optional configuration abilities of an engine beyond editing its documents. */
public enum class ConfigCapability {
    /** Settings can be imported from the engine's own user configuration on this computer. */
    ImportNative,

    /** Packages of the engine can be installed, updated and removed. */
    Packages,

    /** The adapter lists resources (extensions, skills, templates) stored for the profile. */
    Resources,

    /** Values of key-value fields may be kept in the profile vault. */
    Secrets,

    /** Projects need an explicit trust decision before their executable configuration loads. */
    ProjectTrust,
}

/**
 * Declarative configuration of an engine: its documents and the pages the panel draws for them. Reading a schema
 * performs no IO; the panel draws any declared schema without engine-specific code. Keys outside the declared fields
 * stay reachable through the raw editor of each JSON document and are preserved on save.
 */
public data class EngineConfigSchema(
    val documents: List<ConfigDocumentSpec>,
    val pages: List<ConfigPage>,
    /** Keys Heartbeat sets itself; the adapter ignores them at launch and the panel explains where they live. */
    val ownedKeys: List<OwnedKey> = emptyList(),
    val capabilities: Set<ConfigCapability> = emptySet(),
) {
    /** The declared document [id], or null. */
    public fun document(id: ConfigDocumentId): ConfigDocumentSpec? = documents.firstOrNull { it.id == id }
}

/** A key Heartbeat owns, with the reason shown in the panel (for example: «the model is chosen in the chat»). */
public data class OwnedKey(val document: ConfigDocumentId, val path: List<String>, val reason: LocalizedText) {
    init {
        require(path.isNotEmpty() && path.all(String::isNotEmpty)) { "Invalid owned key path" }
    }
}

/** One page of the engine panel. */
public data class ConfigPage(val id: String, val title: LocalizedText, val sections: List<ConfigSection>)

/** A titled group of fields; [note] explains the group (for example how it interacts with Heartbeat). */
public data class ConfigSection(
    val id: String,
    val title: LocalizedText,
    val fields: List<ConfigField>,
    val note: LocalizedText? = null,
)

/**
 * One editable value bound to a document. An absent value means the engine's default ([ConfigFieldKind] defaults are
 * shown, never written). [scopes] narrows where the field applies; [visibleWhen] hides dependent fields.
 */
public data class ConfigField(
    val id: String,
    val title: LocalizedText,
    val binding: ConfigBinding,
    val kind: ConfigFieldKind,
    val description: LocalizedText? = null,
    val scopes: Set<ConfigScope> = ConfigScope.entries.toSet(),
    val isAdvanced: Boolean = false,
    val visibleWhen: ConfigCondition? = null,
)

/** Where a field's value lives. */
public sealed interface ConfigBinding {
    /** The document holding the value. */
    public val document: ConfigDocumentId

    /** A value at [path] of a JSON document; intermediate objects are created on write and pruned when empty. */
    public data class JsonPath(override val document: ConfigDocumentId, val path: List<String>) : ConfigBinding {
        init {
            require(path.isNotEmpty() && path.all(String::isNotEmpty)) { "Invalid JSON path" }
        }
    }

    /** The whole text document. */
    public data class WholeDocument(override val document: ConfigDocumentId) : ConfigBinding
}

/** Shows a field only while [binding] equals [value]; an absent value matches when [isMetWhenAbsent]. */
public data class ConfigCondition(
    val binding: ConfigBinding.JsonPath,
    val value: JsonPrimitive,
    val isMetWhenAbsent: Boolean = false,
)

/** A choice offered for a value. */
public data class ConfigOption(val value: JsonPrimitive, val title: LocalizedText)

/** Unit of a number, for display. */
public enum class ConfigUnit { Tokens, Milliseconds, Seconds }

/** Meaning of a text value, which selects its editor and checks. */
public enum class TextRole {
    Plain,

    /** A file or directory path; relative paths are resolved by the adapter. */
    Path,

    Url,

    /** A command line or executable. */
    Command,
}

/** Syntax of list items. */
public enum class ListSyntax {
    Plain,

    /** Resource patterns: a path, `!glob` to exclude, `+path` to force-include, `-path` to force-exclude. */
    ResourcePatterns,
}

/** Shape of a collection of structured entries. */
public enum class EntriesShape {
    /** A JSON object keyed by entry name (for example MCP servers). */
    NamedMap,

    /** A JSON array. */
    List,
}

/** Value kind of a field and its editor. Defaults describe the engine's own default and are never written. */
public sealed interface ConfigFieldKind {
    /** A boolean. */
    public data class Flag(val isOnByDefault: Boolean? = null) : ConfigFieldKind

    /** One of [options]. */
    public data class Choice(val options: List<ConfigOption>, val default: JsonPrimitive? = null) : ConfigFieldKind

    /** An integer within [min]..[max]. */
    public data class Number(
        val min: Long? = null,
        val max: Long? = null,
        val default: Long? = null,
        val unit: ConfigUnit? = null,
    ) : ConfigFieldKind

    /** A single-line string. */
    public data class Text(
        val role: TextRole = TextRole.Plain,
        val default: String? = null,
        val maxChars: Int = DEFAULT_MAX_VALUE_CHARS,
    ) : ConfigFieldKind

    /** An array of single-line strings. */
    public data class TextList(
        val role: TextRole = TextRole.Plain,
        val syntax: ListSyntax = ListSyntax.Plain,
        val maxItems: Int = DEFAULT_MAX_ITEMS,
    ) : ConfigFieldKind

    /**
     * A JSON object of string values (environment, headers). With [isSecretAllowed] a value may be a reference to the
     * profile vault ([secretReference]); [valueChoices] restricts values (for example tool exposure modes).
     */
    public data class KeyValues(
        val isSecretAllowed: Boolean = false,
        val keyPattern: String? = null,
        val valueChoices: List<ConfigOption>? = null,
        val maxItems: Int = DEFAULT_MAX_ITEMS,
    ) : ConfigFieldKind

    /**
     * Structured entries (MCP servers, packages). Each item matches the first of [variants]; a [EntriesShape.NamedMap]
     * key must match [keyPattern].
     */
    public data class Entries(
        val shape: EntriesShape,
        val itemTitle: LocalizedText,
        val variants: List<EntryVariant>,
        val keyPattern: String? = null,
        val maxItems: Int = DEFAULT_MAX_ITEMS,
    ) : ConfigFieldKind

    /** A whole text document such as Markdown instructions. */
    public data class Document(val language: String? = null) : ConfigFieldKind

    /** Read-only explanation of a setting Heartbeat owns. */
    public data class ManagedNote(val reason: LocalizedText) : ConfigFieldKind
}

/** One form of an entry; the first variant whose [match] accepts an item describes it. */
public data class EntryVariant(
    val id: String,
    val title: LocalizedText,
    val match: EntryMatch,
    val fields: List<EntryField>,
)

/** How an item is recognized as a variant. */
public sealed interface EntryMatch {
    /** The item is a string; its only field has an empty path. */
    public data object Primitive : EntryMatch

    /** The item is an object containing [key]. */
    public data class HasKey(val key: String) : EntryMatch

    /** The item is an object whose [key] equals [value]. */
    public data class KeyEquals(val key: String, val value: JsonPrimitive) : EntryMatch

    /** Any object. */
    public data object AnyObject : EntryMatch
}

/** A field of an entry; [path] is relative to the item and empty only for a [EntryMatch.Primitive] item. */
public data class EntryField(
    val id: String,
    val title: LocalizedText,
    val path: List<String>,
    val kind: ConfigFieldKind,
    val description: LocalizedText? = null,
    val isAdvanced: Boolean = false,
)

/** Default limit of one document. */
public const val DEFAULT_MAX_DOCUMENT_CHARS: Int = 262_144

/** Upper limit of any document. */
public const val MAX_DOCUMENT_CHARS: Int = 1_048_576

/** Default limit of one text value. */
public const val DEFAULT_MAX_VALUE_CHARS: Int = 4_096

/** Default limit of list and map items. */
public const val DEFAULT_MAX_ITEMS: Int = 256

/**
 * Checks that [schema] is consistent: unique identities, bindings to declared documents of a matching format and
 * scope, well-formed kinds. Returns the problems as messages for tests and registration checks; empty when valid.
 */
public fun validateEngineConfigSchema(schema: EngineConfigSchema): List<String> = buildList {
    val documents = schema.documents.associateBy { it.id }
    if (documents.size != schema.documents.size) add("Duplicate document id")
    if (schema.pages.map { it.id }.distinct().size != schema.pages.size) add("Duplicate page id")
    val fields = schema.pages.flatMap { page -> page.sections.flatMap { it.fields } }
    val duplicates = fields.groupBy { it.id }.filterValues { it.size > 1 }.keys
    duplicates.forEach { add("Duplicate field id $it") }
    schema.pages.forEach { page ->
        if (page.sections.map { it.id }.distinct().size != page.sections.size) add("Duplicate section in ${page.id}")
    }
    fields.forEach { field -> addAll(fieldProblems(field, documents)) }
    schema.ownedKeys.forEach { owned ->
        if (documents[owned.document]?.format != ConfigFormat.Json) add("Owned key in a non-JSON document")
    }
}

private fun fieldProblems(field: ConfigField, documents: Map<ConfigDocumentId, ConfigDocumentSpec>): List<String> {
    val document = documents[field.binding.document] ?: return listOf("Field ${field.id}: unknown document")
    val problems = listOfNotNull(
        "scope".takeIf { field.scopes.isEmpty() || !document.scopes.containsAll(field.scopes) },
        bindingProblem(field, document),
        conditionProblem(field, documents),
        kindProblem(field.kind, isEntryItem = false),
    )
    return problems.map { "Field ${field.id}: $it" }
}

private fun conditionProblem(field: ConfigField, documents: Map<ConfigDocumentId, ConfigDocumentSpec>): String? {
    val condition = field.visibleWhen ?: return null
    return "condition".takeIf { documents[condition.binding.document]?.format != ConfigFormat.Json }
}

private fun bindingProblem(field: ConfigField, document: ConfigDocumentSpec): String? {
    val isText = field.kind is ConfigFieldKind.Document
    return when (field.binding) {
        is ConfigBinding.WholeDocument ->
            "a whole document binds a text document".takeUnless { isText && document.format == ConfigFormat.Text }

        is ConfigBinding.JsonPath ->
            "a JSON path binds a JSON document".takeUnless { !isText && document.format == ConfigFormat.Json }
    }
}

private fun kindProblem(kind: ConfigFieldKind, isEntryItem: Boolean): String? = when (kind) {
    is ConfigFieldKind.Choice -> choiceProblem(kind)
    is ConfigFieldKind.Number -> numberProblem(kind)
    is ConfigFieldKind.Text -> "limit".takeIf { kind.maxChars !in 1..MAX_DOCUMENT_CHARS }
    is ConfigFieldKind.TextList -> "limit".takeIf { kind.maxItems < 1 }
    is ConfigFieldKind.KeyValues -> keyValuesProblem(kind)
    is ConfigFieldKind.Entries -> if (isEntryItem) "nested entries" else entriesProblem(kind)
    is ConfigFieldKind.Document -> "document inside an entry".takeIf { isEntryItem }
    is ConfigFieldKind.Flag, is ConfigFieldKind.ManagedNote -> null
}

private fun choiceProblem(kind: ConfigFieldKind.Choice): String? = when {
    kind.options.isEmpty() -> "no options"
    kind.options.map { it.value }.distinct().size != kind.options.size -> "duplicate option"
    kind.default != null && kind.options.none { it.value == kind.default } -> "default is not an option"
    else -> null
}

private fun numberProblem(kind: ConfigFieldKind.Number): String? {
    val default = kind.default
    return when {
        kind.min != null && kind.max != null && kind.min > kind.max -> "min above max"
        default != null && kind.min != null && default < kind.min -> "default below min"
        default != null && kind.max != null && default > kind.max -> "default above max"
        else -> null
    }
}

private fun keyValuesProblem(kind: ConfigFieldKind.KeyValues): String? = when {
    kind.maxItems < 1 -> "limit"
    kind.valueChoices?.isEmpty() == true -> "no value choices"
    kind.keyPattern != null && isInvalidPattern(kind.keyPattern) -> "invalid key pattern"
    else -> null
}

private fun entriesProblem(kind: ConfigFieldKind.Entries): String? = when {
    kind.variants.isEmpty() -> "no variants"
    kind.variants.map { it.id }.distinct().size != kind.variants.size -> "duplicate variant"
    kind.keyPattern != null && isInvalidPattern(kind.keyPattern) -> "invalid key pattern"
    kind.maxItems < 1 -> "limit"
    else -> kind.variants.firstNotNullOfOrNull { variantProblem(it, kind.shape) }
}

private fun variantProblem(variant: EntryVariant, shape: EntriesShape): String? {
    val isPrimitive = variant.match == EntryMatch.Primitive
    val hasEmptyPath = variant.fields.any { it.path.isEmpty() || it.path.any(String::isEmpty) }
    return when {
        isPrimitive && shape == EntriesShape.NamedMap -> "primitive variant of a named map"

        isPrimitive && (variant.fields.size != 1 || variant.fields.single().path.isNotEmpty()) ->
            "primitive variant ${variant.id} needs one field with an empty path"

        !isPrimitive && hasEmptyPath -> "variant ${variant.id} has an empty path"

        variant.fields.map { it.id }.distinct().size != variant.fields.size -> "duplicate entry field"

        else -> variant.fields.firstNotNullOfOrNull { kindProblem(it.kind, isEntryItem = true) }
    }
}

private fun isInvalidPattern(pattern: String): Boolean = runCatching { Regex(pattern) }.exceptionOrNull() != null

private val DocumentId = Regex("[a-z][a-z0-9_]{0,63}")
