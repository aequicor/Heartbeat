package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Exact executable words displayed by the host, without shell expansion. Never log these values. */
@Serializable
public data class ProcessCommandSnapshot(val program: String, val arguments: List<String>, val timeoutMillis: Long) {
    init {
        require(program.isNotBlank() && program.length <= MAX_WORD_CHARS && '\u0000' !in program)
        require(arguments.size <= MAX_ARGUMENTS && arguments.all { it.length <= MAX_WORD_CHARS && '\u0000' !in it })
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
    }

    override fun toString(): String = "ProcessCommandSnapshot(***)"
}

/** Exact typed GitHub payload displayed before approval; correlation markers are host-generated, not consent. */
@Serializable
public data class ProcessGitHubSnapshot(
    val repository: GitHubRepository,
    val change: GitHubChange,
    val key: WriteKey,
) {
    override fun toString(): String = "ProcessGitHubSnapshot(***)"
}

/** A request and exactly one immutable payload, kept outside all script-owned data. Legacy command JSON is valid. */
@Serializable
public data class ProcessCommandDecision(
    val decision: ProcessDecision,
    val command: ProcessCommandSnapshot? = null,
    val github: ProcessGitHubSnapshot? = null,
    val ownership: ProcessOwnershipSnapshot? = null,
) {
    init {
        require(
            listOf(command, github, ownership).count { it != null } == 1,
        ) { "Exactly one decision payload is required" }
    }
    override fun toString(): String = "ProcessCommandDecision(phase=${decision.phase}, ***)"
}

/**
 * The host's durable decision book. Entries are never silently evicted: losing a consumed entry could repeat its
 * external effect. At [MAX_ENTRIES] new requests fail closed; exact requests remain readable and executable.
 * Future archival must preserve consumed identities. This book is not stored in ScriptData.
 */
@Serializable
public data class ProcessDecisionLedger(
    val revision: Long = 0,
    val entries: List<ProcessCommandDecision> = emptyList(),
) {
    init {
        require(revision >= 0 && entries.size <= MAX_ENTRIES)
        require(entries.map { it.decision.id }.toSet().size == entries.size)
        require(entries.map { it.decision.binding }.toSet().size == entries.size)
    }

    /** One id, or null when it has never been issued by this host. */
    public fun find(id: String): ProcessCommandDecision? = entries.singleOrNull { it.decision.id == id }

    /** An exact replay reuses pending, denied, revoked and consumed records alike. */
    public fun find(binding: ProcessActionBinding): ProcessCommandDecision? =
        entries.singleOrNull { it.decision.binding == binding }

    override fun toString(): String = "ProcessDecisionLedger(revision=$revision, entries=${entries.size})"

    /** Journal capacity shared by host admission and persistence. */
    public companion object {
        /** Bounded profile journal; reaching it never causes loss of an execution receipt. */
        public const val MAX_ENTRIES: Int = 256
    }
}

/** Pure mutation inputs used only by the host machine. Script methods never accept a decision answer. */
public sealed interface ProcessDecisionEdit {
    /** Adds a freshly host-issued pending request; an exact binding replay keeps its original record. */
    public data class Request(val entry: ProcessCommandDecision) : ProcessDecisionEdit

    /** Applies a host decision or execution receipt to one known record. */
    public data class Change(val id: String, val command: ProcessDecisionCommand) : ProcessDecisionEdit
}

/**
 * Pure transaction. Null refuses an edit; the unchanged book is an exact request replay. Request can only create
 * a fresh Pending record at revision zero, created no later than the host's time. A claimed or approved record
 * cannot be imported.
 */
public fun ProcessDecisionLedger.reduce(edit: ProcessDecisionEdit, now: Instant): ProcessDecisionLedger? {
    if (revision == Long.MAX_VALUE) return null
    return when (edit) {
        is ProcessDecisionEdit.Request -> request(edit.entry, now)
        is ProcessDecisionEdit.Change -> change(edit, now)
    }
}

private fun ProcessDecisionLedger.request(entry: ProcessCommandDecision, now: Instant): ProcessDecisionLedger? {
    val decision = entry.decision
    val previous = find(decision.binding)
    return when {
        previous != null -> takeIf {
            previous.command == entry.command && previous.github == entry.github &&
                previous.ownership == entry.ownership
        }

        find(decision.id) != null || entries.size >= ProcessDecisionLedger.MAX_ENTRIES -> null

        decision.phase != ProcessDecisionPhase.Pending || decision.revision != 0L -> null

        decision.createdAt > now || decision.expiresAt <= now || decision.updatedAt != decision.createdAt -> null

        else -> copy(revision = revision + 1, entries = entries + entry)
    }
}

private fun ProcessDecisionLedger.change(edit: ProcessDecisionEdit.Change, now: Instant): ProcessDecisionLedger? {
    val previous = find(edit.id)
    val next = previous?.decision?.reduce(edit.command, now)
    return if (previous == null || next == null) {
        null
    } else {
        copy(
            revision = revision + 1,
            entries = entries.map { if (it == previous) it.copy(decision = next) else it },
        )
    }
}

private const val MAX_WORD_CHARS = 8192
private const val MAX_ARGUMENTS = 64
private const val MAX_TIMEOUT_MILLIS = 3_600_000L
