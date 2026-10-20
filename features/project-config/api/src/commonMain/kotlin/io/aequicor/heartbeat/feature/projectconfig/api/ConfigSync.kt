package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** SHA-256 of a unit's files in canonical order (paths and texts); equal digests mean equal files. */
@JvmInline
@Serializable
public value class ConfigDigest(public val value: String) {
    init {
        require(value.isNotBlank()) { "Empty configuration digest" }
    }

    override fun toString(): String = value.take(SHORT)

    private companion object {
        const val SHORT = 12
    }
}

/**
 * A profile entity living in a project folder: unit [unit] of [project] holds entity [key]. [fileDigest] and
 * [entityDigest] are the folder files and the entity's encoding at the last sync, so a later change is attributed to
 * the side that moved: only the files — an incoming proposal; only the entity — a write-back; both — a conflict.
 */
@Serializable
public data class ConfigBinding(
    val project: WorkspaceRef,
    val unit: ConfigUnitRef,
    val key: ConfigEntityKey,
    val fileDigest: ConfigDigest,
    val entityDigest: ConfigDigest,
    /** Explicitly removed from the project by the user; prevents automatic recreation until manually saved. */
    val isExcluded: Boolean = false,
)

/** Result of checking a unit found in a folder or a bundle. */
public sealed interface UnitCheck {
    /** The files equal those of its binding, so they were not checked again. */
    public data object Unchanged : UnitCheck

    /** The unit can be applied; [canonical] is the digest of its canonical files. */
    public data class Valid(val summary: ConfigSummary, val identity: String, val canonical: ConfigDigest) : UnitCheck

    /** The unit cannot be applied. */
    public data class Invalid(val problems: List<ConfigProblem>) : UnitCheck
}

/** A unit of a folder or bundle with the digest of its files. */
public data class ScannedUnit(val ref: ConfigUnitRef, val digest: ConfigDigest, val check: UnitCheck)

/**
 * The current encoding of a profile entity. [identity] is its section identity; the effect reports the entities that
 * are bound to the project and those whose identity an unbound unit of the project matches (in project-scoped
 * sections only those of the project).
 */
public data class EntityDigest(
    val section: ConfigSectionId,
    val key: ConfigEntityKey,
    val identity: String,
    val digest: ConfigDigest,
    /** Unit to create when this project owns the entity and has no files or binding for it. */
    val automaticUnit: ConfigUnitRef? = null,
)

/**
 * What one sync read: units of the folder of [project] and the profile entities they relate to. Sections in
 * [unavailable] (their owner is off or not loaded) are left as they are: their units are not proposed and their
 * bindings are neither removed nor written back.
 */
public data class ProjectSnapshot(
    val project: WorkspaceRef,
    val generation: Long,
    val units: List<ScannedUnit>,
    val entities: List<EntityDigest>,
    val ignored: List<ConfigProblem> = emptyList(),
    val unavailable: Set<ConfigSectionId> = emptySet(),
)

/** Where a proposal comes from. */
@Serializable
public sealed interface ConfigSource {
    /** The `.heartbeat` folder of a project. */
    @Serializable
    public data class Project(val project: WorkspaceRef) : ConfigSource

    /** An opened `.hbai` bundle, applied to the profile. */
    @Serializable
    public data class Bundle(val id: String) : ConfigSource
}

/** What applying a proposal does to the profile. */
public enum class ProposalKind {
    /** Creates an entity. */
    Create,

    /** Replaces the content of [ConfigProposal.target]. */
    Update,

    /** Removes [ConfigProposal.target]: its files are gone. */
    Remove,

    /** Both the files and the entity changed since the last sync: taking the files replaces the entity's changes. */
    Conflict,
}

/**
 * A change the user decides on: unit [unit] of [source] as of [digest] (null for a removal). [problems] make it
 * impossible to trust; [failure] is why the last attempt to apply it failed.
 */
public data class ConfigProposal(
    val source: ConfigSource,
    val unit: ConfigUnitRef,
    val kind: ProposalKind,
    val target: ConfigEntityKey?,
    val digest: ConfigDigest?,
    val summary: ConfigSummary? = null,
    val problems: List<ConfigProblem> = emptyList(),
    val isApplying: Boolean = false,
    val failure: ConfigRejection? = null,
) {
    /** Stable identity of the proposal within its source; a new digest replaces the proposal under the same id. */
    val id: String get() = "${source.label}|$unit"

    /** A valid idle proposal can be applied by the user's explicit decision on this exact digest. */
    public val isTrustable: Boolean get() = problems.isEmpty() && !isApplying

    override fun toString(): String = "ConfigProposal($id, $kind)"
}

private val ConfigSource.label: String
    get() = when (this) {
        is ConfigSource.Project -> "project:${project.value}"
        is ConfigSource.Bundle -> "bundle:$id"
    }

/**
 * Files to write into a project folder from the profile: entity [key] encoded into [unit], or the unit's files deleted
 * when [key] is null (the entity was removed in the app). [expectedDigest] is the source version that authorized
 * this write, or null when the unit was absent; a newer source must be reconciled before anything is overwritten.
 */
public data class ConfigWriteBack(
    val project: WorkspaceRef,
    val unit: ConfigUnitRef,
    val key: ConfigEntityKey?,
    val expectedDigest: ConfigDigest?,
    /** User requested removal from the project while keeping the entity in Heartbeat. */
    val isUnbinding: Boolean = false,
)

/** Outcome of [reconcile] for one project. */
public data class Reconciled(
    val bindings: List<ConfigBinding>,
    val proposals: List<ConfigProposal>,
    val writeBacks: List<ConfigWriteBack>,
)
