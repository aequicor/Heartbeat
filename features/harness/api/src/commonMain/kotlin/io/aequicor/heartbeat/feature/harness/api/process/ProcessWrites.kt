package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * What a process may do to systems outside the profile. Every level is granted on its own and none implies
 * another: a process that may merge may not necessarily publish. The platform only enforces that a write cites a
 * granted level; what a level means for a concrete system is up to the harness.
 *
 * The grants are a declaration of the harness's own approved script, sent as [ProcessCommand.Grant] like any other
 * command. They are not an approval by a person and not a security boundary: nothing stops a script from granting
 * itself a level, and nothing checks that the level named in [ProcessCommand.BeginWrite] matches what the call does.
 * A step only a person may open has to be enforced by the host and does not exist yet.
 */
@Serializable
public enum class ProcessAuthority {
    /** Ordinary, easily undone changes: comments, labels, pushes to the process's own draft branch. */
    @SerialName("write")
    Write,

    /** Making work visible or ready for others: opening a non-draft change, requesting review. */
    @SerialName("publish")
    Publish,

    /** Integrating work into a protected line. */
    @SerialName("merge")
    Merge,

    /** Tagging, releasing or deploying. */
    @SerialName("release")
    Release,
}

/** How far one external write got. */
@Serializable
public sealed interface WritePhase {
    /** Journaled before the call to the external system; the call may not have happened yet. */
    @Serializable
    @SerialName("intended")
    public data object Intended : WritePhase

    /** Whether the call happened is not known, for example after a restart or a timeout. Must be reconciled. */
    @Serializable
    @SerialName("unknown")
    public data object Unknown : WritePhase

    /** The write took effect; [result] points at the evidence. */
    @Serializable
    @SerialName("confirmed")
    public data class Confirmed(val result: ResultRef) : WritePhase

    /** The write is known not to have taken effect, so repeating it cannot duplicate it. */
    @Serializable
    @SerialName("not_applied")
    public data object NotApplied : WritePhase
}

/**
 * One logical write to an external system. [key] identifies the operation, [target] what it touches. [tries] counts
 * how often the operation was begun: it grows only after a [WritePhase.NotApplied] outcome, and a mark or settlement
 * that names an older try never changes a newer one. [at] is the time of the last change of [phase].
 */
@Serializable
public data class ExternalWrite(
    val key: WriteKey,
    val authority: ProcessAuthority,
    val target: ResultRef,
    val phase: WritePhase,
    val tries: Int = 1,
    val at: Instant,
) {
    init {
        require(tries >= 1) { "Invalid write tries" }
    }

    /** Whether the outcome is still to be settled, which forbids any repetition of the same [key]. */
    public val isUnresolved: Boolean get() = phase is WritePhase.Intended || phase is WritePhase.Unknown

    /** Whether [phase] is final: confirmed or known not applied. */
    public val isSettled: Boolean get() = !isUnresolved
}
