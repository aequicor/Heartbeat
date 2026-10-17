package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Quotas that keep one [ProcessRecord] small enough for a single `ScriptData` key: the whole namespace of a
 * harness holds at most `HarnessLimits.DATA_BYTES` bytes, shared by all of its processes. Every text field of the
 * record is bounded here, so [RECORD_BYTES] is a proven ceiling, not a hope.
 */
public object ProcessLimits {
    /** Longest [ProcessId]; `process_<id>` stays a valid `ScriptData` key (at most 64 characters). */
    public const val ID_CHARS: Int = 40

    /** Longest [StageName] and [ResultRef] kind slug. */
    public const val STAGE_CHARS: Int = 32

    /** Longest opaque identifier inside an [ExecutorRef] or [ResultRef]. */
    public const val REF_CHARS: Int = 128

    /** Closed attempts a record retains; older ones are dropped, except the evidence listed on [ProcessRecord]. */
    public const val HISTORY: Int = 20

    /** Distinct stages one process may use. */
    public const val STAGES: Int = 10

    /** Longest [WriteKey]. */
    public const val WRITE_KEY_CHARS: Int = 48

    /** External writes a record journals; settled ones are dropped oldest first, unresolved ones never are. */
    public const val WRITES: Int = 8

    /** Ceiling of the UTF-8 JSON of one fully loaded record. */
    public const val RECORD_BYTES: Int = 24 * 1024

    /**
     * Processes one harness keeps at the same time, ended ones included until they are removed. Eight records at
     * [RECORD_BYTES] stay below `HarnessLimits.DATA_BYTES`, so the namespace quota cannot be what stops a process.
     */
    public const val PROCESSES: Int = 8
}

/**
 * Host-generated identity of one development process, `[a-z0-9_]{1,40}`. The persisted record of a process
 * lives under [storageKey], which is a valid `ScriptData` key by construction.
 */
@Serializable
@JvmInline
public value class ProcessId(public val value: String) {
    init {
        require(value.length <= ProcessLimits.ID_CHARS && ID_PATTERN.matches(value)) { "Invalid ProcessId" }
    }

    /** `ScriptData` key of this process: `process_<id>`. */
    public val storageKey: String get() = "process_$value"

    override fun toString(): String = "ProcessId($value)"
}

/**
 * Harness-defined stage slug, `[a-z][a-z0-9_]{0,31}`. The platform does not know what a stage means: stage graphs and
 * their rules (including everything GitHub-specific) stay in the versioned harness.
 */
@Serializable
@JvmInline
public value class StageName(public val value: String) {
    init {
        require(value.length <= ProcessLimits.STAGE_CHARS && SLUG_PATTERN.matches(value)) { "Invalid StageName" }
    }

    override fun toString(): String = "StageName($value)"
}

/**
 * Idempotency key of one logical external write, `[a-z][a-z0-9_]{0,47}`, chosen by the harness. The same key always
 * means the same write: put whatever makes the operation different (a revision, a head) into it, because the journal
 * refuses to repeat a key whose outcome is unresolved and recognizes a confirmed one as done.
 */
@Serializable
@JvmInline
public value class WriteKey(public val value: String) {
    init {
        require(value.length <= ProcessLimits.WRITE_KEY_CHARS && SLUG_PATTERN.matches(value)) { "Invalid WriteKey" }
    }

    override fun toString(): String = "WriteKey($value)"
}

/**
 * Full object id of the thing a process works on (a git commit): 40 or 64 lowercase hex digits. A prefix is not an
 * identity, so abbreviations are rejected.
 */
@Serializable
@JvmInline
public value class SubjectSha(public val value: String) {
    init {
        require(SHA_PATTERN.matches(value)) { "Invalid SubjectSha" }
    }

    override fun toString(): String = "SubjectSha(${value.take(SHORT_SHA)})"
}

/** What kind of actor runs one attempt of a stage. */
@Serializable
public enum class ExecutorKind {
    /** A harness workflow run. */
    @SerialName("workflow")
    Workflow,

    /** A background helper session. */
    @SerialName("helper")
    Helper,

    /** A cell of an organic-AI organism. */
    @SerialName("organism")
    Organism,

    /** A fixed local command. */
    @SerialName("command")
    Command,

    /** A person, who answers outside any agent session. */
    @SerialName("human")
    Human,

    /** Anything else, for example a remote service. */
    @SerialName("external")
    External,
}

/**
 * Opaque reference to whoever executes an attempt: a [kind] and the executor's own identifier. The identifier is
 * bounded visible ASCII (no spaces, quotes or backslashes) and is never logged; the process only stores it so that
 * a restart can ask the executor's owner what became of the attempt.
 */
@Serializable
public data class ExecutorRef(val kind: ExecutorKind, val id: String) {
    init {
        require(isPlainToken(id)) { "Invalid ExecutorRef" }
    }

    override fun toString(): String = "ExecutorRef(kind=$kind, ***)"
}

/**
 * Reference to the evidence of a stage result, for example a plan comment or a verification report. [kind] is a
 * harness-defined slug (`[a-z][a-z0-9_]{0,31}`) and [id] an opaque bounded identifier. The result itself is never
 * copied into the process: the record only proves which attempt produced what, against which input and subject.
 */
@Serializable
public data class ResultRef(val kind: String, val id: String) {
    init {
        require(kind.length <= ProcessLimits.STAGE_CHARS && SLUG_PATTERN.matches(kind) && isPlainToken(id)) {
            "Invalid ResultRef"
        }
    }

    override fun toString(): String = "ResultRef(kind=$kind, ***)"
}

private const val SHORT_SHA = 7
private val VISIBLE_ASCII = 0x21..0x7E

private val ID_PATTERN = Regex("[a-z0-9_]+")
private val SLUG_PATTERN = Regex("[a-z][a-z0-9_]*")
private val SHA_PATTERN = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

/**
 * Visible ASCII without quote and backslash: one byte per character and no JSON escaping, which is what makes
 * [ProcessLimits.RECORD_BYTES] a ceiling that holds.
 */
private fun isPlainToken(value: String): Boolean = value.isNotEmpty() &&
    value.length <= ProcessLimits.REF_CHARS &&
    value.all { it.code in VISIBLE_ASCII && it != '"' && it != '\\' }
