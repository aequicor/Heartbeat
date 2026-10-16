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
) {
    /** The draft has changes and nothing prevents saving them. */
    val canSave: Boolean get() = isDirty && blockingProblems == 0 && !hasConflict && !isSubmitted
}

/** A configuration page of the engine. */
@Immutable
data class ConfigPageUi(val id: String, val title: TextUi, val sections: ImmutableList<ConfigSectionUi>)

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
    /** A switch; [value] null shows [default]. */
    data class Flag(val value: Boolean?, val default: Boolean?) : FieldEditorUi

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

    /** A structured value with [count] items, edited as JSON of its document. */
    data class Structured(val count: Int) : FieldEditorUi
}

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
}

/** A value the user typed or chose for a field. Never printed: values may be paths or server addresses. */
sealed interface FieldInputUi {
    /** Switches a flag. */
    data class Flag(val value: Boolean) : FieldInputUi

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
}

/**
 * Unsaved profile configuration: [values] edited from the configuration at [base], typed numbers that are not
 * numbers yet by field, and whether a save of it was accepted ([isSubmitted]). Never printed.
 */
data class ConfigDraft(
    val base: ConfigRevision,
    val values: EngineConfigValues,
    val invalidNumbers: Map<String, String> = emptyMap(),
    val isSubmitted: Boolean = false,
) {
    override fun toString(): String = "ConfigDraft(base=${base.value}, isSubmitted=$isSubmitted)"
}
