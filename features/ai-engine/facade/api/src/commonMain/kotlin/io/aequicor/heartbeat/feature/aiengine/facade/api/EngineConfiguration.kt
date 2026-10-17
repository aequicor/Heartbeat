package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.secrets.Secret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configuration of engines through the control panel: settings, instructions, resources and tool servers declared by
 * an engine's [EngineConfigSchema]. Off by default; while off (or while [EngineManagementEnabled] is off) adapters
 * start exactly as without configuration and every engine command line stays unchanged.
 */
public val EngineConfigurationEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.engine_configuration",
    "Настройка ИИ-движков через панель управления: настройки, инструкции, ресурсы, MCP",
    default = true,
)

/**
 * Profile configuration of every engine that declares a schema, plus its project files. Reading [state] performs no
 * IO on projects; project files are read and written only on request. Saving the profile configuration restarts idle
 * runtimes of the engine; accepted turns keep running with the configuration they started with. Values, paths and
 * secrets are never logged. Failures are [ConfigException].
 */
public interface EngineConfiguration {
    /** Current profile configuration and its changes; [EngineConfigurationState.Off] while the toggles are off. */
    public val state: StateFlow<EngineConfigurationState>

    /**
     * Saves the profile configuration of [engine] when it still has [expected] revision and returns the new one.
     * Blocking problems of [ConfigEdit.values] are refused with [ConfigFailure.Invalid]; vault changes of [edit] are
     * applied before the values, and vault entries no reference uses any more are deleted.
     */
    public suspend fun saveProfile(engine: EngineId, edit: ConfigEdit, expected: ConfigRevision): ConfigRevision

    /** Reads the configuration files of [engine] inside the saved project [workspace]. */
    public suspend fun readProject(engine: EngineId, workspace: WorkspaceRef): ProjectConfig

    /**
     * Writes the project files of [engine] when they still have [expected] revisions. Readable documents absent from
     * [edit] are deleted; documents that do not parse stay unless [ProjectEdit.raw] replaces them. Project files never
     * receive vault references.
     */
    public suspend fun saveProject(
        engine: EngineId,
        workspace: WorkspaceRef,
        edit: ProjectEdit,
        expected: ProjectRevision,
    ): ProjectConfig

    /** Saved trust decision for [workspace] and whether the project has configuration that needs one. */
    public suspend fun projectTrust(engine: EngineId, workspace: WorkspaceRef): ProjectTrustStatus

    /** Saves the trust decision for [workspace]; idle runtimes restart with it. */
    public suspend fun setProjectTrust(engine: EngineId, workspace: WorkspaceRef, trust: ProjectTrust)

    /** What the engine's own user configuration on this computer would import. */
    public suspend fun previewImport(engine: EngineId): ImportPreview

    /** Imports [selection] into the profile configuration when it still has [expected] revision. */
    public suspend fun importNative(
        engine: EngineId,
        selection: ImportSelection,
        expected: ConfigRevision,
    ): ImportReport

    /**
     * Resource files of [engine] in the profile and, with [workspace], in that saved project, with whether the saved
     * configuration loads each. Needs [ConfigCapability.Resources]; the files are read on every call.
     */
    public suspend fun resources(engine: EngineId, workspace: WorkspaceRef?): ResourceInventory =
        throw ConfigException(ConfigFailure.Unsupported)
}

/** Configuration service of hosts without engine configuration; always [EngineConfigurationState.Off]. */
public class UnavailableEngineConfiguration : EngineConfiguration {
    override val state: StateFlow<EngineConfigurationState> =
        MutableStateFlow(EngineConfigurationState.Off).asStateFlow()

    override suspend fun saveProfile(engine: EngineId, edit: ConfigEdit, expected: ConfigRevision): Nothing =
        unsupported()

    override suspend fun readProject(engine: EngineId, workspace: WorkspaceRef): Nothing = unsupported()

    override suspend fun saveProject(
        engine: EngineId,
        workspace: WorkspaceRef,
        edit: ProjectEdit,
        expected: ProjectRevision,
    ): Nothing = unsupported()

    override suspend fun projectTrust(engine: EngineId, workspace: WorkspaceRef): Nothing = unsupported()

    override suspend fun setProjectTrust(engine: EngineId, workspace: WorkspaceRef, trust: ProjectTrust): Nothing =
        unsupported()

    override suspend fun previewImport(engine: EngineId): Nothing = unsupported()

    override suspend fun importNative(
        engine: EngineId,
        selection: ImportSelection,
        expected: ConfigRevision,
    ): Nothing = unsupported()

    private fun unsupported(): Nothing = throw ConfigException(ConfigFailure.Unsupported)
}

/** Configuration of every engine with a schema, in catalog order. */
public data class EngineConfigurationState(
    val isEnabled: Boolean = false,
    val engines: List<EngineConfigState> = emptyList(),
) {
    /** The configuration of [engine], or null when it declares no schema. */
    public fun engine(engine: EngineId): EngineConfigState? = engines.firstOrNull { it.engine == engine }

    /** Constants of the state. */
    public companion object {
        /** Engine configuration is off. */
        public val Off: EngineConfigurationState = EngineConfigurationState()
    }
}

/** Profile configuration of one engine. [problems] are the warnings of the saved values. */
public data class EngineConfigState(
    val engine: EngineId,
    val schema: EngineConfigSchema,
    val profile: EngineConfigValues,
    val revision: ConfigRevision,
    val problems: List<ConfigProblem> = emptyList(),
    /** Trust decisions per saved project. */
    val trust: Map<WorkspaceRef, ProjectTrust> = emptyMap(),
)

/** Revision of a profile configuration; every save increments it. */
public data class ConfigRevision(val value: Long)

/** A profile save: the new [values] and vault changes by slot (null deletes the entry). Never printed. */
public data class ConfigEdit(val values: EngineConfigValues, val secrets: Map<String, Secret?> = emptyMap()) {
    override fun toString(): String = "ConfigEdit(values=$values, secrets=${secrets.size})"
}

/**
 * Configuration files of an engine inside a project. [unreadable] keeps documents that exist but do not parse, as
 * raw text; they are edited only as text and are not part of [values].
 */
public data class ProjectConfig(
    val engine: EngineId,
    val workspace: WorkspaceRef,
    val values: EngineConfigValues,
    val revision: ProjectRevision,
    val unreadable: Map<ConfigDocumentId, String> = emptyMap(),
    val problems: List<ConfigProblem> = emptyList(),
) {
    override fun toString(): String =
        "ProjectConfig(engine=${engine.value}, values=$values, unreadable=${unreadable.keys.map { it.value }})"
}

/**
 * A project save: the new [values] and documents written verbatim ([raw], for example a JSON file with comments that
 * is edited as text). Never printed.
 */
public data class ProjectEdit(val values: EngineConfigValues, val raw: Map<ConfigDocumentId, String> = emptyMap()) {
    override fun toString(): String = "ProjectEdit(values=$values, raw=${raw.keys.map { it.value }})"
}

/** Content revision of every project document; null for a document absent on disk. */
public data class ProjectRevision(val documents: Map<ConfigDocumentId, String?> = emptyMap())

/** Whether a project's executable configuration (settings, tool servers, extensions) loads. */
public enum class ProjectTrust {
    /** No decision yet; treated like [Content] and the studio asks once. */
    Undecided,

    /** Only instructions and skills of the project load; its settings and executable resources do not. */
    Content,

    /** Everything the project configures loads. */
    Trusted,
}

/** The saved decision and whether the project has configuration that loads only when trusted. */
public data class ProjectTrustStatus(val trust: ProjectTrust, val hasGatedConfiguration: Boolean)

/** What an import from the engine's own configuration would bring. [source] is a display location, never logged. */
public data class ImportPreview(val source: String, val items: List<ImportItem>) {
    override fun toString(): String = "ImportPreview(items=${items.map { it.id }})"
}

/** One importable part (a document, a resource directory, the package list) with what will change. */
public data class ImportItem(
    val id: String,
    val title: LocalizedText,
    val count: Int? = null,
    val notes: List<ImportNote> = emptyList(),
)

/** Parts to import; [mode] decides what happens to values already configured. */
public data class ImportSelection(val items: Set<String>, val mode: ImportMode = ImportMode.Merge)

/** How imported values combine with the profile configuration. */
public enum class ImportMode {
    /** Imported values replace the same keys; other keys stay. */
    Merge,

    /** Imported documents replace the profile documents entirely. */
    Replace,
}

/** What an import did. */
public data class ImportReport(val imported: List<String>, val notes: List<ImportNote> = emptyList())

/** Something the import changed or skipped, at [path] of [item]. */
public data class ImportNote(val item: String, val reason: ImportNoteReason, val path: List<String> = emptyList())

/** Why an import note was made. */
public enum class ImportNoteReason {
    /** Heartbeat sets this key itself; it was not imported. */
    OwnedKeyDropped,

    /** A plain-text credential was moved into the profile vault. */
    SecretMovedToVault,

    /** The value refers to a variable of the user's shell, which Heartbeat does not pass to the engine. */
    HostVariableNotPassed,

    /** A file was skipped (a link, an installed dependency, an unsupported kind). */
    Skipped,

    /** The document or directory exceeds the limits. */
    TooLarge,

    /** The file could not be read or parsed. */
    Unreadable,
}

/** A refused configuration operation. */
public class ConfigException(public val failure: ConfigFailure) : Exception(failure.toString())

/** Why a configuration operation was refused. */
public sealed interface ConfigFailure {
    /** The values have blocking problems. */
    public data class Invalid(val problems: List<ConfigProblem>) : ConfigFailure

    /** The configuration changed since it was read; read it again. */
    public data object Conflict : ConfigFailure

    /** The project is not saved, not on disk or not readable. */
    public data object ProjectUnavailable : ConfigFailure

    /** The engine's own configuration was not found on this computer. */
    public data object NativeMissing : ConfigFailure

    /** Reading or writing the files failed. */
    public data object Storage : ConfigFailure

    /** The engine or host does not support the operation, or engine configuration is off. */
    public data object Unsupported : ConfigFailure
}
