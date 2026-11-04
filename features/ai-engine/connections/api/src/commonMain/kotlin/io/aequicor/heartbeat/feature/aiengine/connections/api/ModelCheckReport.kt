package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Host-minted UUID, never a credential fingerprint, user label or model-provided identifier. */
@Serializable
public data class ModelCheckGeneration(val value: String) {
    init {
        require(value.matches(GENERATION)) { "Invalid model check generation" }
    }

    override fun toString(): String = "ModelCheckGeneration"
}

/**
 * Identity of one checked route. [source] retains kind, origin, API prefix, owner/location/reference and revision,
 * but its label must be empty. It contains only credential references, never credential material.
 * Unknown auth revision requires a fresh owner generation after reload and whenever its context is invalidated;
 * loading that generation from an old report cannot establish unchanged credentials. Selection UUID alone cannot
 * do this. Consumers revalidate the full route and active run before publishing or using a stored observation.
 */
@Serializable
public data class ModelCheckRevision(
    val target: EngineTarget,
    val selection: ModelCheckGeneration,
    val source: AuthSource,
    /** Required on disk: changing probe/evaluator semantics must invalidate an older receipt, never relabel it. */
    val definition: Int,
    val localAuth: ModelCheckGeneration? = null,
) {
    init {
        require(definition > 0) { "Invalid model check definition" }
        require(source.info.label.isEmpty()) { "Model check context must not contain a source label" }
        require(source.info.revision != AuthRevision.Unknown || localAuth != null) { "Unknown auth needs a generation" }
        val identifiers = listOf(target.engine.value, target.binding.value, target.model.value)
        require(identifiers.all { it.length <= MAX_ID_CHARS }) {
            "Model check route exceeds bounds"
        }
        require(source.scope.origin.value.length <= MAX_ID_CHARS) { "Model check origin exceeds bounds" }
        val revision = source.info.revision
        require(revision !is AuthRevision.Known || revision.fingerprint.length <= MAX_REVISION_CHARS) {
            "Model check auth revision exceeds bounds"
        }
    }

    override fun toString(): String = "ModelCheckRevision"

    /** Versioned host probe/evaluation contract, independent of the provider model's version. */
    public companion object {
        /** Increment when probes, assessment, identity/isolation requirements or completion semantics change. */
        public const val CURRENT_DEFINITION: Int = 1
    }
}

/** A problem observed in this fixed probe, not a general assessment of model safety or factual accuracy. */
@Serializable
public enum class ModelCheckProblem { EmptyAnswer, UnexpectedAnswer, SuspiciousLink, RequestsSecret }

/** Why no complete probe result exists. None of these reasons grants model readiness. */
@Serializable
public enum class ModelCheckIncomplete {
    NotRun,
    IsolationUnproven,
    TokenLimitUnproven,
    Timeout,
    Busy,
    Cancelled,
    RouteChanged,
    ResponseTooLarge,
    Unavailable,
}

/** Outcome of one independent aspect; raw provider exceptions are never part of it. */
@Serializable
public sealed interface ModelCheckOutcome {
    /** A completed, bounded response met this particular aspect's expectation. */
    @Serializable
    public data object Passed : ModelCheckOutcome

    /** The complete answer exhibited [problem]. */
    @Serializable
    public data class Failed(val problem: ModelCheckProblem) : ModelCheckOutcome

    /** The aspect could not be established. */
    @Serializable
    public data class Incomplete(val reason: ModelCheckIncomplete) : ModelCheckOutcome
}

/**
 * Bounded diagnostic text for a host-authored probe. Answer text is untrusted: show as plain text without active
 * links and never log it. Character bounds protect storage/display; they are not provider generation token limits.
 */
@Serializable
public data class ModelCheckAspect(
    val probe: String,
    val expected: String,
    val answer: String?,
    val outcome: ModelCheckOutcome,
) {
    init {
        require(probe.isNotBlank() && probe.length <= MAX_PROBE_CHARS) { "Model check probe exceeds bounds" }
        require(expected.isNotBlank() && expected.length <= MAX_EXPECTED_CHARS) {
            "Model check expectation exceeds bounds"
        }
        require(answer == null || answer.length <= MAX_ANSWER_CHARS) { "Model check answer exceeds bounds" }
        require(outcome != ModelCheckOutcome.Passed || !answer.isNullOrBlank()) { "A successful probe needs an answer" }
        require(outcome !is ModelCheckOutcome.Failed || answer != null) { "An observed failure needs an answer" }
    }

    override fun toString(): String = "ModelCheckAspect(outcome=$outcome)"

    /** Storage/display limits, independent of the runtime's separately enforced output-token budget. */
    public companion object {
        /** Maximum host-authored probe length. */
        public const val MAX_PROBE_CHARS: Int = 1024

        /** Maximum expectation description length. */
        public const val MAX_EXPECTED_CHARS: Int = 512

        /** Maximum captured answer length. Request one extra character to detect an overlong facade answer. */
        public const val MAX_ANSWER_CHARS: Int = 1024
    }
}

/**
 * Two independent observations for one exact revision/run. This is historical evidence, not an admission permit.
 * A different run or current identity must not silently consume it. Both successes have no warning or detail badge:
 * the owner stores only a text-free [ModelCheckReceipt], discarding this transient report's probe/answer text.
 */
@Serializable
public data class ModelCheckReport(
    val revision: ModelCheckRevision,
    val run: ModelCheckGeneration,
    val checkedAt: Instant,
    val response: ModelCheckAspect,
    val accuracy: ModelCheckAspect,
) {
    /** A warning/detail affordance is relevant only after the owner established that this report is current. */
    public val hasWarning: Boolean
        get() = response.outcome != ModelCheckOutcome.Passed || accuracy.outcome != ModelCheckOutcome.Passed

    override fun toString(): String = "ModelCheckReport(warning=$hasWarning)"
}

/**
 * Persisted completion metadata. Success retains no diagnostic report or answer text. A warning retains both aspects,
 * including whichever aspect succeeded, for the details dialog. Absence of a receipt means Unknown, not success.
 * This receipt still needs current revision/run validation and never grants authentication or session admission.
 */
@Serializable
public data class ModelCheckReceipt(
    val revision: ModelCheckRevision,
    val run: ModelCheckGeneration,
    val checkedAt: Instant,
    val isPassed: Boolean,
    val report: ModelCheckReport?,
) {
    init {
        require(if (isPassed) report == null else report != null && report.hasWarning) { "Invalid model check receipt" }
        report?.let {
            require(it.revision == revision && it.run == run && it.checkedAt == checkedAt) {
                "Model check receipt identity mismatch"
            }
        }
    }

    override fun toString(): String = "ModelCheckReceipt(passed=$isPassed)"

    /** Conversions preserve two-aspect evidence for warnings, not success diagnostics. */
    public companion object {
        /** Drops successful answer text before storage. */
        public fun from(report: ModelCheckReport): ModelCheckReceipt = ModelCheckReceipt(
            report.revision,
            report.run,
            report.checkedAt,
            !report.hasWarning,
            report.takeIf { it.hasWarning },
        )
    }
}

private val GENERATION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private const val MAX_ID_CHARS = 1024
private const val MAX_REVISION_CHARS = 256
