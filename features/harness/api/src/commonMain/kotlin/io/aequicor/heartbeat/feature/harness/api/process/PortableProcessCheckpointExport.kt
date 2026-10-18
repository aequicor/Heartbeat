package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.json.Json

/** Hard ceiling applied before checkpoint bytes leave or enter the host. */
public const val PORTABLE_PROCESS_CHECKPOINT_BYTES: Int = 128 * 1024

/** Matches the source registry's lifetime capacity; imported tombstones must never be evicted. */
public const val PORTABLE_GITHUB_OPERATIONS: Int = 128

/**
 * Strict wire decoder for bytes whose SHA-256 the host already verified against the immutable checkpoint pointer.
 * Rejects oversized input before parsing, invalid UTF-8, unknown fields and unsupported versions. Decoder failures
 * propagate to the host's sanitized error boundary; exception messages may contain private input and must not log.
 */
public fun decodePortableProcessCheckpoint(bytes: ByteArray): PortableProcessCheckpoint {
    require(bytes.size <= PORTABLE_PROCESS_CHECKPOINT_BYTES) { "Portable checkpoint is too large" }
    return Json.decodeFromString(bytes.decodeToString(throwOnInvalidSequence = true))
}

/** Why the deliberately narrow portable format cannot safely represent a process. */
public enum class PortableProcessRefusal {
    LiveAttempt,
    UnsupportedWait,
    UnresolvedWrite,
    NativeStopUnconfirmed,
    MissingEvidence,
    IncompleteRegistry,
    IdentityMismatch,
    RecordConflict,
    TooLarge,
    InvalidRecord,
    DefinitionMismatch,
}

/** Export never silently drops unsupported or unresolved state. */
public sealed interface PortableProcessExport {
    /** Verified allowlisted manifest, still requiring immutable storage before coordinator export. */
    public data class Ready(val checkpoint: PortableProcessCheckpoint) : PortableProcessExport

    /** The original state is retained; no partial manifest may leave the host. */
    public data class Refused(val reason: PortableProcessRefusal) : PortableProcessExport
}

/**
 * Host-only receipts supplied after closing admissions. [isNativeStopped] includes every non-workflow executor;
 * terminal workflow status alone is insufficient. [registryEntries] is the independently read count for this exact
 * process lifetime, including old keys no longer retained by ProcessRecord.writes. Tombstones require canonical
 * request digests and sealed evidence; the host must not infer these facts from script-writable data.
 */
public data class PortableProcessReceipts(
    val definitionDigest: String,
    val isNativeStopped: Boolean,
    val evidence: Map<ResultRef, PortableEvidence>,
    val registryEntries: Int,
    val github: List<PortableGitHubTombstone>,
) {
    init {
        require(registryEntries in 0..PORTABLE_GITHUB_OPERATIONS && isPortableDigest(definitionDigest))
    }

    override fun toString(): String = "PortableProcessReceipts(***)"
}

/** Creates no authority; the host must durably store its immutable result before Export on the coordinator. */
public fun ProcessRecord.portableCheckpoint(
    identity: GitHubTaskIdentity,
    receipts: PortableProcessReceipts,
): PortableProcessExport {
    val refusal = portableRefusal(receipts)
    if (refusal != null) return PortableProcessExport.Refused(refusal)
    val history = history.map {
        val outcome = it.outcome
        PortableProcessAttempt(
            it.attempt.number,
            it.attempt.stage,
            it.attempt.inputRevision,
            it.attempt.subject,
            it.attempt.startedAt,
            it.finishedAt,
            (outcome as? AttemptOutcome.Succeeded)?.let { success -> receipts.evidence.getValue(success.result) },
            (outcome as? AttemptOutcome.Failed)?.failure,
        )
    }
    val writes = writes.map {
        val refusal = it.portableRefusal(receipts.github)
        PortableProcessWrite(
            it.key,
            it.authority,
            if (refusal == null) receipts.evidence.getValue(it.target) else null,
            (it.phase as? WritePhase.Confirmed)?.let { confirmed -> receipts.evidence.getValue(confirmed.result) },
            it.tries,
            it.at,
            refusal,
        )
    }
    val ended = phase as? ProcessPhase.Ended
    val checkpoint = PortableProcessCheckpoint(
        identity, receipts.definitionDigest, id, incarnation, createdAt, updatedAt, inputRevision, subject,
        nextAttempt, nextWait, nextWriteTry,
        history, writes, receipts.github, ended?.end, ended?.at,
    )
    return if (Json.encodeToString(checkpoint).encodeToByteArray().size > PORTABLE_PROCESS_CHECKPOINT_BYTES) {
        PortableProcessExport.Refused(PortableProcessRefusal.TooLarge)
    } else {
        PortableProcessExport.Ready(checkpoint)
    }
}

private fun ProcessRecord.portableRefusal(receipts: PortableProcessReceipts): PortableProcessRefusal? = when {
    inFlight != null -> PortableProcessRefusal.LiveAttempt

    phase is ProcessPhase.Waiting -> PortableProcessRefusal.UnsupportedWait

    unresolvedWrites().isNotEmpty() -> PortableProcessRefusal.UnresolvedWrite

    !hasPortableChronology() -> PortableProcessRefusal.InvalidRecord

    !receipts.isNativeStopped || history.any { it.attempt.workflow?.isSettled == false } ->
        PortableProcessRefusal.NativeStopUnconfirmed

    !receipts.hasCompleteRegistry(inputRevision) -> PortableProcessRefusal.IncompleteRegistry

    !hasPortableEvidence(receipts) -> PortableProcessRefusal.MissingEvidence

    else -> null
}

private fun ProcessRecord.hasPortableChronology(): Boolean {
    val end = phase as? ProcessPhase.Ended
    val isEndValid = end == null || end.at in createdAt..updatedAt
    val areWritesValid = writes.all { it.tries < Int.MAX_VALUE && it.at in createdAt..updatedAt }
    val areAttemptsValid = history.all {
        it.attempt.startedAt >= createdAt && it.finishedAt <= updatedAt && it.finishedAt >= it.attempt.startedAt
    }
    return nextWriteTry > 0 && isEndValid && areWritesValid && areAttemptsValid
}

private fun PortableProcessReceipts.hasCompleteRegistry(inputRevision: Long): Boolean =
    registryEntries == github.size && github.map { it.key }.distinct().size == github.size &&
        github.all { it.inputRevision <= inputRevision }

private fun ProcessRecord.hasPortableEvidence(receipts: PortableProcessReceipts): Boolean {
    val areAllSealed = requiredEvidence(receipts.github).all { it in receipts.evidence }
    val seals = receipts.evidence.values + receipts.github.mapNotNull { it.result }
    return areAllSealed && seals.groupBy { it.digest }.values.all { it.distinct().size == 1 }
}

private fun ProcessRecord.requiredEvidence(github: List<PortableGitHubTombstone>): List<ResultRef> =
    history.mapNotNull { (it.outcome as? AttemptOutcome.Succeeded)?.result } +
        writes.flatMap {
            listOfNotNull(
                it.target.takeIf { _ -> it.portableRefusal(github) == null },
                (it.phase as? WritePhase.Confirmed)?.result,
            )
        }

/**
 * Matches only a terminal pre-send refusal by permanent key and exact native marker or imported audit digest.
 * This proof records that nothing was dispatched; it never substitutes for remote evidence of an external effect.
 */
public fun ExternalWrite.portableRefusal(github: List<PortableGitHubTombstone>): PortableGitHubTombstone? {
    if (phase != WritePhase.NotApplied) return null
    return github.singleOrNull { tombstone ->
        tombstone.key == key && tombstone.refusal != null && target.matchesRefusal(tombstone)
    }
}

private fun ResultRef.matchesRefusal(tombstone: PortableGitHubTombstone): Boolean = when (kind) {
    "github_operation" -> id == tombstone.marker
    "handover_refusal" -> id == tombstone.payloadDigest
    else -> false
}

/** Import yields a plan, never writes storage, starts an attempt, schedules a wake or replays an external write. */
public sealed interface PortableProcessImport {
    /**
     * The host must persist the evidence catalog and all GitHub tombstones before publishing this record, and keep
     * admission closed until fresh external reconciliation and coordinator activation succeed. Registry retries
     * match payloadDigest and preserve marker; absence of the original payload is never permission to send again.
     */
    public data class Ready(
        val record: ProcessRecord,
        val evidence: List<PortableEvidence>,
        val github: List<PortableGitHubTombstone>,
    ) : PortableProcessImport

    /** No import writes or dispatch may follow this result. */
    public data class Refused(val reason: PortableProcessRefusal) : PortableProcessImport
}

/**
 * Projects verified bytes into the recipient's explicitly approved [definition]. Original grants and decisions are
 * deliberately absent. [approvedDefinitionDigest] is independently computed by the recipient host from its reviewed
 * source and workflow declarations, never copied from this checkpoint as an approval.
 * Any different existing record refuses import, even in the same lifetime, so import cannot
 * rewind counters or overwrite work. Synthetic historical executors are audit-only and contain no native identity.
 */
public fun PortableProcessCheckpoint.prepareImport(
    expectedIdentity: GitHubTaskIdentity,
    definition: ProcessDefinition,
    approvedDefinitionDigest: String,
    existing: ProcessRecord?,
): PortableProcessImport {
    if (identity.canonical != expectedIdentity.canonical) {
        return PortableProcessImport.Refused(PortableProcessRefusal.IdentityMismatch)
    }
    if (definitionDigest != approvedDefinitionDigest) {
        return PortableProcessImport.Refused(PortableProcessRefusal.DefinitionMismatch)
    }
    if (Json.encodeToString(this).encodeToByteArray().size > PORTABLE_PROCESS_CHECKPOINT_BYTES) {
        return PortableProcessImport.Refused(PortableProcessRefusal.TooLarge)
    }
    val record = ProcessRecord(
        id = process,
        createdAt = createdAt,
        updatedAt = updatedAt,
        inputRevision = inputRevision,
        subject = subject,
        phase = end?.let { ProcessPhase.Ended(it, checkNotNull(endedAt)) } ?: ProcessPhase.Idle,
        history = history.map {
            val attempt = ProcessAttempt(
                it.number,
                it.stage,
                ExecutorRef(ExecutorKind.External, "handover_${it.number}"),
                it.inputRevision,
                it.subject,
                it.startedAt,
            )
            val outcome = it.result?.let { result -> AttemptOutcome.Succeeded(result.asResultRef()) }
                ?: AttemptOutcome.Failed(checkNotNull(it.failure))
            AttemptRecord(attempt, outcome, it.finishedAt)
        },
        nextAttempt = nextAttempt,
        nextWait = nextWait,
        writes = writes.map {
            ExternalWrite(
                it.key,
                it.authority,
                it.target?.asResultRef() ?: ResultRef("handover_refusal", checkNotNull(it.refusal).payloadDigest),
                it.result?.let { result -> WritePhase.Confirmed(result.asResultRef()) } ?: WritePhase.NotApplied,
                it.tries,
                it.at,
            )
        },
        incarnation = incarnation,
        definition = definition,
    )
    if (existing != null && existing != record) {
        return PortableProcessImport.Refused(PortableProcessRefusal.RecordConflict)
    }
    val evidence = (
        history.mapNotNull { it.result } + writes.flatMap { listOfNotNull(it.target, it.result) } +
            github.mapNotNull { it.result }
    ).distinct()
    if (evidence.groupBy { it.digest }.values.any { it.size != 1 }) {
        return PortableProcessImport.Refused(PortableProcessRefusal.MissingEvidence)
    }
    return PortableProcessImport.Ready(record, evidence, github)
}
