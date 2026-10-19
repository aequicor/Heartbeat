package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable

/** Why a folder or bundle was not read; never carries paths or texts. */
public enum class ConfigReadFailure {
    /** The platform cannot read configuration trees. */
    Unsupported,

    /** The project folder or bundle does not exist or cannot be opened. */
    Unavailable,

    /** `.heartbeat` is a symbolic link or not a directory; it is never followed. */
    NotDirectory,

    /** The tree exceeds [ConfigLimits]. */
    Limit,

    /** The bundle has no valid manifest, duplicates or entries outside the tree. */
    Format,

    /** The manifest declares a newer version than [ConfigLayout.VERSION]. */
    Version,

    /** Reading failed. */
    Io,
}

/** A profile entity of a section, as the user selects it for export or for a project folder. */
@Serializable
public data class ConfigEntityRef(val section: ConfigSectionId, val key: ConfigEntityKey)

/** Sync state of one project folder; [generation] fences late results of earlier syncs. */
public data class ProjectSync(
    val generation: Long = 0,
    val isSyncing: Boolean = false,
    val isDirty: Boolean = false,
    val hasFolder: Boolean = false,
    val failure: ConfigReadFailure? = null,
    val ignored: List<ConfigProblem> = emptyList(),
)

/** A `.hbai` bundle opened for import into the profile; [name] is its file name, never a path. */
public data class ConfigBundle(val id: String, val name: String) {
    override fun toString(): String = "ConfigBundle($id)"
}

/** The one long operation of the machine at a time; [project] is the folder it writes into, if any. */
public data class ConfigTask(val requestId: String, val kind: ConfigTaskKind, val project: WorkspaceRef? = null)

/** Kinds of [ConfigTask]. */
public enum class ConfigTaskKind { SaveToProject, Export, OpenBundle, ImportToProject }

/** How a [ConfigTask] ended. */
public sealed interface ConfigTaskResult {
    /** Entities written into a project folder; [skipped] ones collided with other files there. */
    public data class Saved(val units: Int, val skipped: Int) : ConfigTaskResult

    /** A bundle of [units] was written. */
    public data class Exported(val units: Int) : ConfigTaskResult

    /** A bundle was opened with [proposals] for the profile. */
    public data class Opened(val proposals: Int) : ConfigTaskResult

    /**
     * A bundle's [units] were copied into a project folder, to be proposed by its next sync; [skipped] were already
     * there with the same files or were kept by [ExistingUnits.Skip].
     */
    public data class Imported(val units: Int, val skipped: Int = 0) : ConfigTaskResult

    /**
     * Nothing was imported: the project folder already has [units] of the bundle with other files, and the import
     * asked first ([ExistingUnits.Ask]). The user chooses to overwrite or skip them and imports again.
     */
    public data class ImportConflicts(val units: List<ConfigUnitRef>) : ConfigTaskResult

    /** Nothing or only part was done. */
    public data class Failed(val reason: ConfigTaskFailure) : ConfigTaskResult
}

/** What an import into a project does with units its folder already has with other files. */
public enum class ExistingUnits {
    /** Nothing is written while there are such units: the task ends with [ConfigTaskResult.ImportConflicts]. */
    Ask,

    /** The bundle's files replace them; files of a replaced unit that the bundle lacks are deleted. */
    Overwrite,

    /** They stay as they are; only the other units are copied. */
    Skip,
}

/** Why a [ConfigTask] failed. */
public enum class ConfigTaskFailure {
    Busy,
    Unavailable,
    Unreadable,
    Format,
    Version,
    Limit,
    Unwritable,
    Empty,

    /** A file name is not portable (a name Windows reserves, such as `con` or `aux`): nothing was written. */
    Name,
}

/** Project configuration of the profile: bindings, project folders, proposals and the current bundle. */
public sealed interface ProjectConfigState : MachineState {
    /** The feature is off or the platform has no local projects: nothing is read, written or proposed. */
    public data object Off : ProjectConfigState

    /** Reading the stored bindings for [projects]. */
    public data class Loading(val projects: Set<WorkspaceRef>) : ProjectConfigState

    /** Cancels project operations before saving a location change; completion reloads bindings and settings. */
    public data class Configuring(val projects: Set<WorkspaceRef>) : ProjectConfigState

    /** The stored bindings are unreadable: nothing is synced or written until a reload succeeds. */
    public data class Failed(val projects: Set<WorkspaceRef>) : ProjectConfigState

    /**
     * Bindings are known. [reviewing] and [writing] are reviews and write-backs in flight, [dismissed] proposals the
     * user set aside (`id@digest`, until the files change); [revision] orders saves of [bindings].
     */
    public data class Ready(
        val bindings: List<ConfigBinding> = emptyList(),
        val projects: Map<WorkspaceRef, ProjectSync> = emptyMap(),
        val proposals: List<ConfigProposal> = emptyList(),
        val reviewing: Set<String> = emptySet(),
        val writing: Set<ConfigWriteBack> = emptySet(),
        val dismissed: Set<String> = emptySet(),
        val bundle: ConfigBundle? = null,
        val task: ConfigTask? = null,
        val revision: Long = 0,
        val storage: ConfigStorageSettings = ConfigStorageSettings(),
    ) : ProjectConfigState
}

/**
 * Commands of other features and of the feature's screen, the feature's own commands, watchers' input and results of
 * effects.
 */
public sealed interface ProjectConfigIntent : MachineIntent {
    /** Commands any feature may send: they read folders and bundles or hide proposals, never trust or write. */
    public sealed interface Public : ProjectConfigIntent {
        /** Reads the bindings again after a failed load. */
        public data object Reload : Public

        /** Reads the folder of [project] again. */
        public data class Sync(val project: WorkspaceRef) : Public

        /** Sets [proposal] with [digest] aside until its files change. */
        public data class Dismiss(val proposal: String, val digest: ConfigDigest?) : Public

        /** Opens the bundle at [path] for import into the profile; its proposals still need the user's trust. */
        public data class OpenBundle(val requestId: String, val path: String) : Public {
            override fun toString(): String = "OpenBundle($requestId)"
        }

        /** Closes the open bundle and drops its proposals. */
        public data object CloseBundle : Public
    }

    /**
     * Decisions and writes of the feature's own screen (trusting a proposal, writing project folders and bundles),
     * watchers' input and results of effects. Only `project-config:impl` sends them, like
     * `HarnessIntent.Internal.Edit`: no other feature can trust configuration or write files through the machine.
     */
    public sealed interface Internal : ProjectConfigIntent {
        /**
         * Applies [proposal] the user trusts in the version with [digest] they saw; [isRiskAccepted] confirms a risky,
         * uncertain or unreviewed one. A proposal whose files changed since is ignored.
         */
        public data class Trust(val proposal: String, val digest: ConfigDigest?, val isRiskAccepted: Boolean = false) :
            Internal

        /** Rejects the files of a project [proposal] with [digest] and writes the profile's version back instead. */
        public data class KeepApp(val proposal: String, val digest: ConfigDigest?) : Internal

        /** Deletes the files of [unit] from the folder of [project]; the profile entity stays. */
        public data class RemoveFromProject(val project: WorkspaceRef, val unit: ConfigUnitRef) : Internal

        /** Writes [entities] into the folder of [project] and binds them there. */
        public data class SaveToProject(
            val requestId: String,
            val project: WorkspaceRef,
            val entities: List<ConfigEntityRef>,
        ) : Internal

        /** Writes [entities] into a bundle at [path]. */
        public data class Export(val requestId: String, val path: String, val entities: List<ConfigEntityRef>) :
            Internal {
            override fun toString(): String = "Export($requestId, ${entities.size} entities)"
        }

        /** Copies the units of the bundle at [path] into the folder of [project]; [existing] decides on clashes. */
        public data class ImportToProject(
            val requestId: String,
            val path: String,
            val project: WorkspaceRef,
            val existing: ExistingUnits = ExistingUnits.Ask,
        ) : Internal {
            override fun toString(): String = "ImportToProject($requestId, $existing)"
        }

        /** The feature is on for the local [projects]. */
        public data class Enabled(val projects: Set<WorkspaceRef>) : Internal

        /** The feature is off. */
        public data object Disabled : Internal

        /** The user's projects are now [projects]. */
        public data class ProjectsChanged(val projects: Set<WorkspaceRef>) : Internal

        /** The folder of [project] or entities bound to it changed. */
        public data class Changed(val project: WorkspaceRef) : Internal

        /** The stored bindings. */
        public data class Loaded(
            val bindings: List<ConfigBinding>,
            val storage: ConfigStorageSettings = ConfigStorageSettings(),
        ) : Internal

        /** Changes the profile default, preserving all project overrides. */
        public data class SetDefaultStorage(val location: ConfigStorage) : Internal

        /** Overrides one project's location; null restores the profile default. */
        public data class SetProjectStorage(val project: WorkspaceRef, val location: ConfigStorage?) : Internal

        /** The new location is durable and previous project IO has finished. */
        public data object StorageSaved : Internal

        /** Saving failed; reload the durable settings before resuming any project IO. */
        public data object StorageSaveFailed : Internal

        /** The stored bindings are unreadable. */
        public data object LoadFailed : Internal

        /** A sync read [snapshot]; [hasFolder] is false when the project has no `.heartbeat` folder. */
        public data class Synced(val snapshot: ProjectSnapshot, val hasFolder: Boolean = true) : Internal

        /** The syncs of [requests] could not read their folders. */
        public data class SyncFailed(val requests: List<SyncRequest>, val failure: ConfigReadFailure) : Internal

        /** The review of [proposal] at [digest] ended with [review]. */
        public data class Reviewed(val proposal: String, val digest: ConfigDigest, val review: ProposalReview) :
            Internal

        /** [proposal] was applied; a project proposal binds [binding], a removal or bundle one has none. */
        public data class Applied(val proposal: String, val binding: ConfigBinding?) : Internal

        /** [proposal] was not applied. */
        public data class ApplyFailed(val proposal: String, val reason: ConfigRejection) : Internal

        /** [writeBack] was written; [binding] is the new binding, null after deleting the files. */
        public data class WrittenBack(val writeBack: ConfigWriteBack, val binding: ConfigBinding?) : Internal

        /** [writeBacks] could not be written; a later change retries them. */
        public data class WriteBackFailed(val writeBacks: List<ConfigWriteBack>) : Internal

        /** [writeBack]'s files changed before writing: a new sync proposes their current version. */
        public data class WriteBackConflict(val writeBack: ConfigWriteBack) : Internal

        /** The reviews of [proposals] failed as a whole; each becomes [ProposalReview.Unavailable]. */
        public data class ReviewsFailed(val proposals: List<ConfigProposal>) : Internal

        /** The bundle of [requestId] was opened with [proposals]. */
        public data class BundleOpened(
            val requestId: String,
            val bundle: ConfigBundle,
            val proposals: List<ConfigProposal>,
        ) : Internal

        /** The task [requestId] ended with [result]; [bindings] are new bindings it created. */
        public data class TaskDone(
            val requestId: String,
            val result: ConfigTaskResult,
            val bindings: List<ConfigBinding> = emptyList(),
        ) : Internal

        /** The bindings could not be written; they stay in memory for this run. */
        public data object BindingsSaveFailed : Internal
    }
}

/** IO commands executed in the feature impl; lists are processed independently, one result intent per element. */
public sealed interface ProjectConfigEffect : MachineEffect {
    /** Reads the stored bindings. */
    public data object Load : ProjectConfigEffect

    /** Releases the dropped [bundleId], then stores bindings and settings after prior reads are cancelled. */
    public data class SaveStorage(
        val settings: ConfigStorageSettings,
        val bindings: List<ConfigBinding>,
        val bundleId: String? = null,
    ) : ProjectConfigEffect

    /** Stores [bindings]; ignored when a newer [revision] was already stored. */
    public data class SaveBindings(val bindings: List<ConfigBinding>, val revision: Long) : ProjectConfigEffect

    /** Reads the folders of [requests]: each answers with Synced or SyncFailed. */
    public data class Sync(val requests: List<SyncRequest>) : ProjectConfigEffect

    /** Reviews [proposals]: each answers with Reviewed. */
    public data class Review(val proposals: List<ConfigProposal>) : ProjectConfigEffect

    /** Applies [proposal] exactly as reviewed: answers with Applied or ApplyFailed. */
    public data class Apply(val proposal: ConfigProposal) : ProjectConfigEffect

    /** Writes [writeBacks] into project folders: each answers with WrittenBack or WriteBackFailed. */
    public data class WriteBack(val writeBacks: List<ConfigWriteBack>) : ProjectConfigEffect

    /** Writes [entities] into the folder of [project]; answers with TaskDone and the new bindings. */
    public data class SaveToProject(
        val requestId: String,
        val project: WorkspaceRef,
        val entities: List<ConfigEntityRef>,
    ) : ProjectConfigEffect

    /** Writes [entities] into a bundle at [path]; answers with TaskDone. */
    public data class Export(val requestId: String, val path: String, val entities: List<ConfigEntityRef>) :
        ProjectConfigEffect {
        override fun toString(): String = "Export($requestId, ${entities.size} entities)"
    }

    /** Opens the bundle at [path] as bundle [requestId]; answers with BundleOpened or TaskDone. */
    public data class OpenBundle(val requestId: String, val path: String) : ProjectConfigEffect {
        override fun toString(): String = "OpenBundle($requestId)"
    }

    /** Copies the bundle at [path] into the folder of [project] as [existing] says; answers with TaskDone. */
    public data class ImportToProject(
        val requestId: String,
        val path: String,
        val project: WorkspaceRef,
        val existing: ExistingUnits,
    ) : ProjectConfigEffect {
        override fun toString(): String = "ImportToProject($requestId, $existing)"
    }

    /** Forgets the files of bundle [id]. */
    public data class CloseBundle(val id: String) : ProjectConfigEffect
}

/** One folder to read: [bindings] are those of [project], so unchanged units are not checked again. */
public data class SyncRequest(val project: WorkspaceRef, val generation: Long, val bindings: List<ConfigBinding>)

/** One-shot notifications for screens. */
public sealed interface ProjectConfigOutput : MachineOutput {
    /** The task [requestId] ended with [result]. */
    public data class TaskFinished(val requestId: String, val result: ConfigTaskResult) : ProjectConfigOutput

    /** Bindings could not be read or written. */
    public data object StorageFailed : ProjectConfigOutput

    /** A project write failed; its proposal or binding remains so the user can retry the action. */
    public data object WriteBackFailed : ProjectConfigOutput
}

/** Profile-scoped machine of project configuration. */
public object ProjectConfigMachineKey :
    MachineKey<
        ProjectConfigState,
        ProjectConfigIntent,
        ProjectConfigIntent.Public,
        ProjectConfigEffect,
        ProjectConfigOutput,
    > {
    override val name: String = "project-config"
}
