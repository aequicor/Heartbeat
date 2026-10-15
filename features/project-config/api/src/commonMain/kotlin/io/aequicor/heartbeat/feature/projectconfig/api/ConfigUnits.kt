package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Section of a tree: the first path segment of its files, owned by one feature. Stored on disk — never renamed. */
@JvmInline
@Serializable
public value class ConfigSectionId(public val value: String) {
    init {
        require(ID.matches(value)) { "Invalid configuration section" }
    }

    override fun toString(): String = value

    /** Sections of this application. */
    public companion object {
        private val ID = Regex("[a-z][a-z0-9_]{0,31}")

        /** Harnesses: metadata, skills, instructions, templates, scripts, workflows and tool policy. */
        public val Harnesses: ConfigSectionId = ConfigSectionId("harnesses")

        /** Interfaces of harnesses; a unit belongs to the harness unit it names. */
        public val Interfaces: ConfigSectionId = ConfigSectionId("interfaces")

        /** Learned instructions and skills. */
        public val Learning: ConfigSectionId = ConfigSectionId("learning")
    }
}

/**
 * One unit of a section: the files of one entity, e.g. harness `review` or interface `review/board`. [name] is one or
 * more path segments chosen by the section; together with [section] it identifies the unit inside a tree.
 */
@Serializable
public data class ConfigUnitRef(val section: ConfigSectionId, val name: String) {
    init {
        require(ConfigPath.isValid(name)) { "Invalid configuration unit name" }
    }

    override fun toString(): String = "$section/$name"
}

/** Files of one unit, sorted by path, all inside the section directory; [toString] never shows texts. */
public data class ConfigUnit(val ref: ConfigUnitRef, val files: List<ConfigFile>) {
    init {
        require(files.all { it.path.section == ref.section.value && it.path.inSection.isNotEmpty() }) {
            "Unit files must be inside the section directory"
        }
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate unit file" }
        require(files.size <= ConfigLimits.UNIT_FILES) { "Too many unit files" }
    }

    /** UTF-8 size of all files. */
    public val bytes: Long get() = files.sumOf { it.bytes.toLong() }

    override fun toString(): String = "ConfigUnit($ref, ${files.size} files)"

    /** Construction of units. */
    public companion object {
        /** Unit of [files] in canonical order. */
        public fun of(ref: ConfigUnitRef, files: Collection<ConfigFile>): ConfigUnit =
            ConfigUnit(ref, files.sortedBy { it.path.value })
    }
}

/** Opaque profile identity of an entity inside its section (harness id, surface id, instruction id). */
@JvmInline
@Serializable
public value class ConfigEntityKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "Empty configuration entity key" }
    }

    override fun toString(): String = value
}

/**
 * A profile entity a section can encode. [name] is the unit name it is written to by default; [revision] changes
 * whenever its portable content may have changed, so unchanged entities are not encoded again. [project] is the
 * project the entity applies in (learned instructions), [parent] the entity it belongs to (an interface's harness).
 */
public data class ConfigEntity(
    val key: ConfigEntityKey,
    val name: String,
    val title: String,
    val revision: String,
    val description: String = "",
    val project: WorkspaceRef? = null,
    val parent: ConfigEntityKey? = null,
) {
    override fun toString(): String = "ConfigEntity($key, name=$name, revision=$revision)"
}

/** What a unit contains, for previews; names only, never texts or code. */
public data class ConfigSummary(
    val title: String,
    val description: String = "",
    val contents: List<ConfigContent> = emptyList(),
) {
    /** Whether applying the unit installs code that runs in the app (scripts, script tools, workflows). */
    public val hasCode: Boolean get() = contents.any { it.kind.isCode }

    override fun toString(): String = "ConfigSummary(${contents.size} contents)"
}

/** One named part of a unit. */
public data class ConfigContent(val kind: ConfigContentKind, val name: String)

/** Kinds of unit parts shown in previews. */
public enum class ConfigContentKind(public val isCode: Boolean = false) {
    Skill,
    Instruction,
    Template,
    Script(isCode = true),
    Workflow(isCode = true),
    ToolPolicy,
    Interface,
    LearnedInstruction,
    LearnedSkill,
}

/** Result of checking a unit before it is reviewed or applied. */
public sealed interface ConfigCheck {
    /** The unit can be applied. */
    public data class Valid(val summary: ConfigSummary) : ConfigCheck

    /** The unit cannot be applied. */
    public data class Invalid(val problems: List<ConfigProblem>) : ConfigCheck {
        init {
            require(problems.isNotEmpty()) { "An invalid unit needs a problem" }
        }
    }
}

/**
 * Why a file or unit cannot be used. [detail] is a short technical hint such as a JSON path or a compiler line
 * number; it never contains file texts, code or secrets.
 */
public data class ConfigProblem(val code: ConfigProblemCode, val path: ConfigPath? = null, val detail: String = "")

/** Kinds of [ConfigProblem]. */
public enum class ConfigProblemCode {
    /** The file is not valid JSON or does not match the schema. */
    Syntax,

    /** A file the unit needs is absent. */
    Missing,

    /** The file is not part of the format, or the tree version is newer than [ConfigLayout.VERSION]. */
    Unsupported,

    /** A name is not a valid identity of the section. */
    Name,

    /** A size or count limit of the section is exceeded. */
    Limit,

    /** Code does not compile. */
    Compile,

    /** The unit refers to a parent unit that is not available. */
    Parent,
}

/** Where an applied unit goes. [project] binds project-scoped entities; [parent] is the applied parent entity. */
public data class ConfigApplyContext(val project: WorkspaceRef?, val parent: ConfigEntityKey? = null)

/** Outcome of applying or removing a unit. */
public sealed interface ConfigApplyResult {
    /** The entity [key] now holds the unit, or was removed. */
    public data class Applied(val key: ConfigEntityKey) : ConfigApplyResult

    /** Nothing changed. */
    public data class Rejected(val reason: ConfigRejection) : ConfigApplyResult
}

/** Why a section did not apply or remove a unit. */
public enum class ConfigRejection {
    /** The owner feature is disabled, not loaded or busy for longer than it waits. */
    Unavailable,

    /** The target changed since it was read. */
    Conflict,

    /** Another entity already has this identity. */
    Duplicate,

    /** A limit of the owner library is reached. */
    Limit,

    /** The unit does not pass the check. */
    Invalid,

    /** The parent entity does not exist. */
    MissingParent,

    /** The target entity does not exist. */
    NotFound,

    /** Storage or runtime failure. */
    Failed,
}
