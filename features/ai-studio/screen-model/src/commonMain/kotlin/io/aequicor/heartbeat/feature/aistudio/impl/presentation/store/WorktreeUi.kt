package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/** Screen phases of the profile-owned task. */
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
enum class WorktreeActionUi { CreatePr, Merge, Refine, Leave }

/** Restoration of the profile journal, separate from individual task failures. */
enum class WorktreeJournalUi { Loading, Ready, Error }

/** Queue and worker progress of a named build. */
enum class BuildPhaseUi { Queued, WaitingForResource, Running, Completed, Cancelled, Failed, Unknown }

/** Bounded build diagnostics exposed by the journal. */
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
@Immutable
data class WorktreeUi(
    val phase: WorktreePhaseUi,
    val branch: String?,
    val sourceBranch: String?,
    val summary: String?,
    val pullRequestUrl: String?,
    val failure: String?,
    val builds: ImmutableList<BuildUi>,
)
