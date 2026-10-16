package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The profile configuration form of an engine: its pages with the values being edited, whether they differ from the
 * saved configuration ([isDirty]) and what keeps them from being saved. [hasConflict] when the configuration was
 * saved elsewhere since editing started; [isSubmitted] while a save runs. [otherProblems] belong to no field.
 */
@Immutable
data class ConfigFormUi(
    val pages: ImmutableList<ConfigPageUi>,
    val isDirty: Boolean = false,
    val blockingProblems: Int = 0,
    val hasConflict: Boolean = false,
    val isSubmitted: Boolean = false,
    val otherProblems: ImmutableList<ConfigProblemUi> = persistentListOf(),
    /** The profile's configuration files, each edited as a whole. */
    val files: ImmutableList<ConfigFileUi> = persistentListOf(),
) {
    /** The draft has changes and nothing prevents saving them. */
    val isSaveable: Boolean get() = isDirty && blockingProblems == 0 && !hasConflict && !isSubmitted
}

/**
 * A configuration file edited as a whole, keys the pages do not show included: its [text] as shown (as typed while
 * it does not parse, [isInvalid]) in [language]. Never printed.
 */
@Immutable
data class ConfigFileUi(
    val id: String,
    val title: TextUi,
    val fileName: String,
    val text: String,
    val language: String?,
    val isInvalid: Boolean = false,
) {
    override fun toString(): String = "ConfigFileUi(id=$id, isInvalid=$isInvalid)"
}

/** A configuration page of the engine. */
@Immutable
data class ConfigPageUi(
    val id: String,
    val title: TextUi,
    val sections: ImmutableList<ConfigSectionUi>,
    val hasResources: Boolean = false,
)

/** A titled group of fields; [note] explains how the group relates to Heartbeat. */
@Immutable
data class ConfigSectionUi(
    val id: String,
    val title: TextUi,
    val note: TextUi?,
    val fields: ImmutableList<ConfigFieldUi>,
)

/**
 * One configuration value: its [editor] with the current value, whether it is set ([isSet] false shows the
 * engine's default) and the [problems] found at it. Values may name the user's paths and servers; never printed.
 */
@Immutable
data class ConfigFieldUi(
    val id: String,
    val title: TextUi,
    val description: TextUi?,
    val editor: FieldEditorUi,
    val isSet: Boolean,
    val isAdvanced: Boolean = false,
    val problems: ImmutableList<ConfigProblemUi> = persistentListOf(),
) {
    override fun toString(): String = "ConfigFieldUi(id=$id, isSet=$isSet, problems=${problems.size})"
}

/** The editor of a field with its value; defaults describe the engine's own default and are never written. */
@Immutable
sealed interface FieldEditorUi {
    /** A switch; [isOn] null shows [isOnByDefault]. */
    data class Flag(val isOn: Boolean?, val isOnByDefault: Boolean?) : FieldEditorUi

    /** One of [options], identified by [OptionUi.key]. */
    data class Choice(val options: ImmutableList<OptionUi>, val selected: String?, val default: String?) :
        FieldEditorUi

    /** An integer as typed: [text] may be an invalid number until it is fixed. */
    data class Number(val text: String, val default: Long?, val min: Long?, val max: Long?, val unit: UnitUi?) :
        FieldEditorUi

    /** A single-line value. */
    data class Text(val text: String, val role: TextRoleUi, val default: String?) : FieldEditorUi

    /** A list of single-line values; [isPatterns] for resource patterns (`!glob`, `+path`, `-path`). */
    data class TextList(val items: ImmutableList<String>, val role: TextRoleUi, val isPatterns: Boolean) :
        FieldEditorUi

    /** A whole text document such as Markdown instructions, in [language] when known. */
    data class Document(val text: String, val language: String?) : FieldEditorUi

    /** A setting Heartbeat owns, with the reason. */
    data class Managed(val reason: TextUi) : FieldEditorUi

    /**
     * Pairs of names and values (environment, headers). [valueChoices] restrict the values; with [isSecretAllowed] a
     * value can be kept in the profile vault instead of the file.
     */
    data class KeyValues(
        val rows: ImmutableList<KeyValueRowUi>,
        val isSecretAllowed: Boolean,
        val valueChoices: ImmutableList<OptionUi>?,
    ) : FieldEditorUi

    /**
     * Structured entries (tool servers, packages) in document order. Named entries ([isNamed]) are renamed in place;
     * a new entry takes one of [variants] (keyed by variant id).
     */
    data class Entries(
        val itemTitle: TextUi,
        val isNamed: Boolean,
        val variants: ImmutableList<OptionUi>,
        val items: ImmutableList<EntryUi>,
    ) : FieldEditorUi
}

/**
 * One entry: its [key] (the name, or the position in a list), the [variant] it matched (null for a form none
 * describes, which is edited only as JSON), its fields and the problems of the entry itself.
 */
@Immutable
data class EntryUi(
    val key: String,
    val variant: String?,
    val fields: ImmutableList<ConfigFieldUi>,
    val problems: ImmutableList<ConfigProblemUi> = persistentListOf(),
) {
    override fun toString(): String = "EntryUi(variant=${variant ?: "none"}, fields=${fields.size})"
}

/**
 * A name and its value. A value kept in the vault has a [slot]; it is never shown: [value] is only what was typed
 * to replace it, and [secret] tells whether a value is saved, newly typed or missing. Never printed.
 */
@Immutable
data class KeyValueRowUi(
    val key: String = "",
    val value: String = "",
    val slot: String? = null,
    val secret: SecretUi? = null,
) {
    override fun toString(): String = "KeyValueRowUi(secret=${secret ?: "none"})"
}

/** State of a value kept in the profile vault. */
enum class SecretUi { Saved, Typed, Missing }

/** A choice offered for a value; [key] identifies it in [FieldInputUi.Choice]. */
@Immutable
data class OptionUi(val key: String, val title: TextUi)

/** Unit of a number. */
enum class UnitUi { Tokens, Milliseconds, Seconds }

/** Meaning of a text value, which selects its keyboard and hint. */
enum class TextRoleUi { Plain, Path, Url, Command }

/** A problem at [path] relative to its field; [isBlocking] problems prevent saving. */
@Immutable
data class ConfigProblemUi(
    val reason: ConfigProblemReasonUi,
    val path: ImmutableList<String> = persistentListOf(),
    val isBlocking: Boolean = true,
)

/** Why a value is rejected or questionable; mirrors the facade reasons plus local typing errors. */
enum class ConfigProblemReasonUi {
    UnknownDocument,
    WrongFormat,
    NotInScope,
    TooLong,
    WrongType,
    NotAnOption,
    OutOfRange,
    InvalidKey,
    ControlCharacter,
    UnknownVariant,
    Owned,
    SecretInProject,
    MissingSecret,
    SecretLiteral,

    /** The typed text is not an integer. */
    NotANumber,

    /** The file has comments or is not plain JSON; its values are edited in the file editor. */
    Unparsed,
}

/** A value the user typed or chose for a field. Never printed: values may be paths or server addresses. */
sealed interface FieldInputUi {
    /** Switches a flag. */
    data class Flag(val isOn: Boolean) : FieldInputUi

    /** Chooses the option with [key]. */
    data class Choice(val key: String) : FieldInputUi

    /** Types a number; a blank text returns to the default. */
    data class Number(val text: String) : FieldInputUi {
        override fun toString(): String = "Number(***)"
    }

    /** Types a value; an empty text returns to the default. */
    data class Text(val text: String) : FieldInputUi {
        override fun toString(): String = "Text(***)"
    }

    /** Replaces the list; blank items stay while edited and are dropped on save. */
    data class TextList(val items: ImmutableList<String>) : FieldInputUi {
        override fun toString(): String = "TextList(${items.size})"
    }

    /** Returns to the engine's default. */
    data object Reset : FieldInputUi

    /** Replaces the pairs of a key-value field; a vault row passes what was typed to replace its value. */
    data class KeyValues(val rows: ImmutableList<KeyValueRowUi>) : FieldInputUi {
        override fun toString(): String = "KeyValues(${rows.size})"
    }

    /** Moves the value of the pair [key] into the vault, or back into the file as an empty value. */
    data class SetSecret(val key: String, val isSecret: Boolean) : FieldInputUi

    /** Appends an entry of [variant]. */
    data class AddEntry(val variant: String) : FieldInputUi

    /** Removes the entry [key]. */
    data class RemoveEntry(val key: String) : FieldInputUi

    /** Renames the named entry [key]; a name another entry has changes nothing. */
    data class RenameEntry(val key: String, val name: String) : FieldInputUi {
        override fun toString(): String = "RenameEntry(***)"
    }

    /** Changes the field [field] of the entry [key]. */
    data class EditEntry(val key: String, val field: String, val input: FieldInputUi) : FieldInputUi {
        override fun toString(): String = "EditEntry(field=$field, input=$input)"
    }
}

/**
 * Unsaved profile configuration: [values] edited from the configuration at [base], typed numbers that are not
 * numbers yet by field, values typed for vault slots ([secrets]), whole files as typed ([files]) and whether a
 * save of it was accepted ([isSubmitted]). Never printed.
 */
data class ConfigDraft(
    /** The profile revision the draft was read at; null for a project, whose draft keeps its own revision. */
    val base: ConfigRevision?,
    val values: EngineConfigValues,
    val invalidNumbers: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
    /** Files as typed in their editors, by document id. */
    val files: Map<String, String> = emptyMap(),
    val isSubmitted: Boolean = false,
) {
    override fun toString(): String = "ConfigDraft(base=${base?.value ?: "none"}, isSubmitted=$isSubmitted)"
}

/**
 * Vault slots with a saved value and the values typed for slots in the draft; [isAllowed] where values may move to
 * the vault (the profile, never a project). Never printed.
 */
internal data class SecretView(val saved: Set<String>, val typed: Map<String, String>, val isAllowed: Boolean = true) {
    override fun toString(): String = "SecretView(saved=${saved.size}, typed=${typed.size})"
}
