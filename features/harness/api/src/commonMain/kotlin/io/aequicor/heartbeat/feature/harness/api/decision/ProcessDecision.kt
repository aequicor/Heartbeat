package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Exact host-built identity of an external action. The host computes [actionDigest] from the immutable operation
 * payload, including program, expanded arguments and timeout when executing a fixed command. [repository] is the
 * resolved repository identity, not a display label supplied by a script. [workspace] identifies the actual working
 * copy; both remain bound even when a worktree shares its logical project with another copy.
 *
 * This is evidence to compare, never a capability. A script can construct or serialize it without acquiring consent.
 * Neither [ProcessAuthority] grants nor process records in ScriptData can produce a host decision.
 */
@Serializable
public data class ProcessActionBinding(
    val harness: HarnessId,
    val item: ItemId,
    val harnessRevision: Long,
    val process: ProcessWorkflowKey,
    val inputRevision: Long,
    val subject: SubjectSha,
    val workspace: WorkspaceRef,
    val repository: String,
    val operation: ProcessAuthority,
    val actionDigest: String,
) {
    init {
        require(harnessRevision >= 0 && inputRevision >= 0) { "Invalid action revision" }
        require(repository.isNotBlank() && repository.length <= MAX_REPOSITORY_CHARS) { "Invalid repository" }
        require(DIGEST.matches(actionDigest)) { "Invalid action digest" }
    }

    override fun toString(): String = "ProcessActionBinding(operation=$operation, ***)"
}

/** Durable states of one host-owned decision and its single dispatch. Stored names are a persistence contract. */
@Serializable
public enum class ProcessDecisionPhase {
    /** Waiting for the trusted host UI; callbacks, agent methods and external comments cannot answer. */
    @SerialName("pending")
    Pending,

    /** The user approved exactly this snapshot until its deadline or revocation. */
    @SerialName("approved")
    Approved,

    /** The user refused; an identical request keeps this refusal. */
    @SerialName("denied")
    Denied,

    /** Explicitly revoked; restoring an old UI or script snapshot cannot revive it. */
    @SerialName("revoked")
    Revoked,

    /** Persisted before dispatch; restart must reconcile it, never blindly execute again. */
    @SerialName("executing")
    Executing,

    /** The executor returned a terminal receipt. Exact retries observe it without another execution. */
    @SerialName("completed")
    Completed,
}

/**
 * A record owned by a host-only store, outside ScriptData. [id] and [revision] fence stale UI actions; deadlines are
 * absolute and do not get extended by reads, retries, clock rollback or process restart. The executor must persist
 * Executing before touching an external system, revalidate the live binding and use the external system's own
 * conditional operation (for example expected merge head). An approval does not override tool policy or hooks.
 */
@Serializable
public data class ProcessDecision(
    val id: String,
    val binding: ProcessActionBinding,
    val createdAt: Instant,
    val expiresAt: Instant,
    val phase: ProcessDecisionPhase = ProcessDecisionPhase.Pending,
    val revision: Long = 0,
    val updatedAt: Instant = createdAt,
) {
    init {
        require(ID.matches(id)) { "Invalid decision id" }
        require(revision >= 0 && expiresAt > createdAt && updatedAt >= createdAt) { "Invalid decision lifetime" }
    }

    /** Only this exact, unexpired, unrevoked, undispatched host record may be claimed by an executor. */
    public fun permits(current: ProcessActionBinding, now: Instant): Boolean =
        phase == ProcessDecisionPhase.Approved && binding == current && now >= updatedAt && now < expiresAt

    override fun toString(): String = "ProcessDecision(phase=$phase, revision=$revision, ***)"
}

/** Pure host inputs. The script API deliberately exposes no method accepting these commands. */
public sealed interface ProcessDecisionCommand {
    /** A click in the host's native decision screen, fenced to the displayed record revision. */
    public data class Answer(val expectedRevision: Long, val isApproved: Boolean) : ProcessDecisionCommand

    /** Revokes a decision before admission; an executing or completed action cannot be revoked. */
    public data class Revoke(val expectedRevision: Long) : ProcessDecisionCommand

    /** Executor admission, after reconstructing the exact live action binding. */
    public data class Claim(val current: ProcessActionBinding) : ProcessDecisionCommand

    /** A terminal receipt of the one admitted dispatch; revocation remains terminal. */
    public data object Complete : ProcessDecisionCommand
}

/**
 * Pure decision transition. Null means refused with no change. Persistence and provenance checks belong to the host.
 *
 * | From | Input / guard | To |
 * |---|---|---|
 * | Pending | Answer, exact revision, before deadline | Approved or Denied |
 * | Pending, Approved, Denied | Revoke, exact revision | Revoked |
 * | Approved | Claim, equal binding, before deadline | Executing |
 * | Executing | Complete | Completed |
 * | any | stale revision, backwards clock, unsupported input | refused |
 *
 * Admission is the irreversible boundary: revocation cannot cancel a consumed claim. Unknown dispatches remain
 * Executing across restart and expiry and require reconciliation outside this pure transition.
 */
public fun ProcessDecision.reduce(command: ProcessDecisionCommand, now: Instant): ProcessDecision? {
    if (now < updatedAt || revision == Long.MAX_VALUE) return null
    val next = nextPhase(command, now) ?: return null
    return copy(phase = next, revision = revision + 1, updatedAt = now)
}

private fun ProcessDecision.nextPhase(command: ProcessDecisionCommand, now: Instant): ProcessDecisionPhase? =
    when (command) {
        is ProcessDecisionCommand.Answer -> answer(command, now)

        is ProcessDecisionCommand.Revoke -> ProcessDecisionPhase.Revoked.takeIf {
            command.expectedRevision == revision && phase in setOf(
                ProcessDecisionPhase.Pending,
                ProcessDecisionPhase.Approved,
                ProcessDecisionPhase.Denied,
            )
        }

        is ProcessDecisionCommand.Claim -> ProcessDecisionPhase.Executing.takeIf { permits(command.current, now) }

        ProcessDecisionCommand.Complete -> ProcessDecisionPhase.Completed.takeIf {
            phase == ProcessDecisionPhase.Executing
        }
    }

private fun ProcessDecision.answer(command: ProcessDecisionCommand.Answer, now: Instant): ProcessDecisionPhase? {
    if (command.expectedRevision != revision || phase != ProcessDecisionPhase.Pending || now >= expiresAt) return null
    return if (command.isApproved) ProcessDecisionPhase.Approved else ProcessDecisionPhase.Denied
}

private const val MAX_REPOSITORY_CHARS = 2048
private val DIGEST = Regex("[0-9a-f]{64}")
private val ID = Regex("[a-z0-9_]{1,64}")
