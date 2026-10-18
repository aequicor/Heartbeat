package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import kotlinx.serialization.Serializable

/**
 * Requested sandbox execution of a workflow stage. A missing [baseCommit] captures the project's committed HEAD
 * before conception; a full commit from an earlier stage explicitly selects that stage's output. No field grants
 * trust or selects harnesses. Every ceiling is finite and may only narrow the host's maximum growth budget.
 */
@Serializable
public data class OrganismExecution(
    val baseCommit: String? = null,
    val maxCells: Int = 16,
    val maxDepth: Int = 4,
    val maxCases: Int = 24,
    val maxOpenCasesPerCell: Int = 2,
) {
    init {
        require(baseCommit == null || isOrganismCommit(baseCommit)) { "A full Git commit is required" }
        require(
            maxCells in 1..MAX_CELLS && maxDepth in 1..MAX_DEPTH &&
                maxCases in 0..MAX_CASES && maxOpenCasesPerCell in 0..MAX_OPEN_CASES,
        ) {
            "Organism growth exceeds host limits"
        }
    }

    override fun toString(): String = "OrganismExecution(***)"
}

/**
 * Immutable host-captured environment for organism stages. Only admission constructs it from the caller's full
 * selected harness set (including restricting harnesses beyond the contribution limit), or the owning approved
 * harness for a parentless script. Author JSON and prompts never supply this context. Null in legacy routing
 * preserves its old execution contract; an explicitly requested [OrganismExecution] requires a captured context.
 */
@Serializable
public data class WorkflowOrganismContext(val harnesses: Set<HarnessId>) {
    init {
        require(harnesses.isNotEmpty() && harnesses.size <= HarnessLimits.HARNESSES)
    }

    override fun toString(): String = "WorkflowOrganismContext(***)"
}

/** Host-verified Git output of one completed stage; text alone never creates this proof. */
@Serializable
public data class OrganismCommit(val branch: String, val baseCommit: String, val commit: String) {
    init {
        require(branch.isNotBlank() && branch.length <= MAX_BRANCH_CHARS && branch.none { it.isISOControl() })
        require(isOrganismCommit(baseCommit) && isOrganismCommit(commit))
    }

    override fun toString(): String = "OrganismCommit(***)"
}

private fun isOrganismCommit(value: String): Boolean =
    value.length in setOf(SHA1_LENGTH, SHA256_LENGTH) && value.all { it in '0'..'9' || it in 'a'..'f' }

private const val MAX_CELLS = 16
private const val MAX_DEPTH = 4
private const val MAX_CASES = 24
private const val MAX_OPEN_CASES = 2
private const val MAX_BRANCH_CHARS = 256
private const val SHA1_LENGTH = 40
private const val SHA256_LENGTH = 64
