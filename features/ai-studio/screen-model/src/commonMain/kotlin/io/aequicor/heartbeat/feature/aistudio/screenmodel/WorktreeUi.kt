@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Screen phases of the profile-owned task. */
@Serializable
@SerialName("WorktreePhaseUi")
enum class WorktreePhaseUi {
    Preparing,
    Idle,
    Working,
    CompletionSignaled,
    AwaitingDecision,
    ActionWorking,
    Retained,
    RecoveryRequired,
    Failed,
}

/** Explicit result decisions, never inferred from an ordinary reply. */
@Serializable
@SerialName("WorktreeActionUi")
enum class WorktreeActionUi { CreatePr, HandOff, Merge, Refine, Leave }

/** Restoration of the profile journal, separate from individual task failures. */
@Serializable
@SerialName("WorktreeJournalUi")
enum class WorktreeJournalUi { Loading, Ready, Error }

/** Phases of a virtual sandbox, mirroring the worktree journal; [Ready] needs no card of its own. */
@Serializable
@SerialName("SandboxPhaseUi")
enum class SandboxPhaseUi { Ready, Pausing, Waiting, Manual, Rebasing, Conflicted, HandedOff }

/** Queue and worker progress of a named build. */
@Serializable
@SerialName("BuildPhaseUi")
enum class BuildPhaseUi { Queued, WaitingForResource, Running, Completed, Cancelled, Failed, Unknown }

/** Bounded build diagnostics exposed by the journal. */
@Serializable
@SerialName("BuildUi")
@Immutable
data class BuildUi(
    val id: String,
    val command: String,
    val phase: BuildPhaseUi,
    val output: String,
    val failure: String?,
    val queuePosition: Int? = null,
)

/** Saved checkout and completion card, independent of the screen's lifetime. */
@Serializable
@SerialName("WorktreeUi")
@Immutable
data class WorktreeUi(
    val phase: WorktreePhaseUi,
    val branch: String?,
    val sourceBranch: String?,
    val summary: String?,
    val pullRequestUrl: String?,
    val failure: String?,
    val builds: ImmutableList<BuildUi>,
    /** Source-only sandbox whose builds run in shared build slots of the repository. */
    val isSandbox: Boolean = false,
    val taskId: String? = null,
    /** Phase of a virtual sandbox; null for an ordinary worktree. */
    val sandboxPhase: SandboxPhaseUi? = null,
    val isSandboxResolving: Boolean = false,
    val sandboxConflicts: ImmutableList<String> = persistentListOf(),
    val resultCommit: String? = null,
)
