package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The plan of a chat in planning mode or implementing it. [revision] is the base of the user's edit and of the
 * revision the user approves or implements; [text] is user data.
 */
@Serializable
@SerialName("PlanUi")
@Immutable
data class PlanUi(
    val revision: Long,
    val isPlanning: Boolean,
    val status: PlanStatusUi,
    val title: String?,
    val text: String,
    val updatedBy: PlanAuthorUi,
) {
    override fun toString(): String = "PlanUi(revision=$revision, planning=$isPlanning, status=$status)"
}

/** Agreement on the current revision of a plan. */
@Serializable
@SerialName("PlanStatusUi")
enum class PlanStatusUi { Draft, Proposed, Approved }

/** Who changed a plan last. */
@Serializable
@SerialName("PlanAuthorUi")
enum class PlanAuthorUi { Agent, User, External }

/** The agent's request to review a revision of the plan, shown as a card in its reply. */
@Serializable
@SerialName("PlanProposalUi")
@Immutable
data class PlanProposalUi(val revision: Long?, val summary: String)

/** Where the implementation of a plan runs. */
@Serializable
@SerialName("PlanImplementationModeUi")
enum class PlanImplementationModeUi { Chat, Worktree, Sandbox, Organism }
