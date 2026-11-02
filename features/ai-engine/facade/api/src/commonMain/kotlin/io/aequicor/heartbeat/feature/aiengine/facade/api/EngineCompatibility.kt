package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Non-secret identity of one compatibility regression. Values describe the actual process, not the selected
 * display version. [runtimeFingerprint] is a content digest or immutable build identity, never a path/account.
 * A changed external executable needs a fresh stamp and negotiation even when its version string is unchanged.
 */
public data class EngineCompatibilityStamp(
    val engine: EngineId,
    val heartbeat: String,
    val connector: String,
    val runtime: String,
    val protocol: Int,
    val configurationSchema: Int,
    val historySchema: Int,
    val nativeTarget: String,
    val runtimeFingerprint: String,
) {
    override fun toString(): String = "EngineCompatibilityStamp"
}

/** Independently observed identity facts. Unknown fields are null and never replaced with guessed defaults. */
public data class EngineCompatibilityIdentity(
    val engine: EngineId? = null,
    val heartbeat: String? = null,
    val connector: String? = null,
    val runtime: String? = null,
    val protocol: Int? = null,
    val configurationSchema: Int? = null,
    val historySchema: Int? = null,
    val nativeTarget: String? = null,
    val runtimeFingerprint: String? = null,
) {
    override fun toString(): String = "EngineCompatibilityIdentity"
}

/** Converts a complete regression identity to independently inspectable facts without granting admission. */
public fun EngineCompatibilityStamp.identity(): EngineCompatibilityIdentity = EngineCompatibilityIdentity(
    engine = engine,
    heartbeat = heartbeat,
    connector = connector,
    runtime = runtime,
    protocol = protocol,
    configurationSchema = configurationSchema,
    historySchema = historySchema,
    nativeTarget = nativeTarget,
    runtimeFingerprint = runtimeFingerprint,
)

/**
 * Connector-owned hard constraints. An absent upper bound permits newer versions after successful baseline
 * negotiation; verified regressions are separate from compatibility. Versions use strict SemVer without a v
 * prefix. A prerelease participates in ordering and is never silently treated as the corresponding final release.
 * Constraints must be backed by the connector's versioned protocol/runtime research.
 */
public data class EngineCompatibilityRequirements(
    val engine: EngineId,
    val heartbeatVersions: Set<String>,
    val connectorVersions: Set<String>,
    val minimumRuntime: String,
    val maximumRuntimeExclusive: String? = null,
    val deniedRuntimeVersions: Set<String> = emptySet(),
    val protocols: Set<Int>,
    val configurationSchemas: Set<Int>,
    val historySchemas: Set<Int>,
    val nativeTargets: Set<String>,
    val baselineCapabilities: Set<String>,
    val verified: Set<EngineCompatibilityStamp> = emptySet(),
) {
    init {
        require(heartbeatVersions.isNotEmpty() && heartbeatVersions.all { it.isNotBlank() }) { "Missing host contract" }
        require(
            connectorVersions.isNotEmpty() && connectorVersions.all { it.isNotBlank() },
        ) { "Missing connector contract" }
        val minimum = requireNotNull(CompatibilityVersion.parse(minimumRuntime)) { "Invalid minimum runtime" }
        if (maximumRuntimeExclusive != null) {
            val maximum = requireNotNull(CompatibilityVersion.parse(maximumRuntimeExclusive)) {
                "Invalid maximum runtime"
            }
            require(maximum > minimum) { "Empty runtime range" }
        }
        require(deniedRuntimeVersions.all { CompatibilityVersion.parse(it) != null }) { "Invalid denied runtime" }
        require(protocols.isNotEmpty() && protocols.all { it > 0 }) { "Missing protocol contract" }
        require(configurationSchemas.isNotEmpty() && configurationSchemas.all { it >= 0 }) { "Missing config contract" }
        require(historySchemas.isNotEmpty() && historySchemas.all { it >= 0 }) { "Missing history contract" }
        require(nativeTargets.isNotEmpty() && nativeTargets.all { it.isNotBlank() }) { "Missing target contract" }
        require(baselineCapabilities.all { it.isNotBlank() }) { "Empty baseline capability" }
    }

    override fun toString(): String = "EngineCompatibilityRequirements"
}

/**
 * Facts established by the connector for the exact candidate. Null means unobserved; false is positive evidence
 * of failure. A successful version command alone cannot establish baseline, provenance or capabilities.
 * No credentials, user paths, prompts or raw provider errors belong in this record.
 */
public data class EngineCompatibilityEvidence(
    val identity: EngineCompatibilityIdentity = EngineCompatibilityIdentity(),
    val isBaselineConfirmed: Boolean? = null,
    val isProvenanceConfirmed: Boolean? = null,
    val capabilities: Set<String>? = null,
) {
    override fun toString(): String = "EngineCompatibilityEvidence"
}

/** Why compatibility could not be confirmed; only typed, non-sensitive diagnostics are exposed. */
public enum class CompatibilityProblem {
    /** One or more identity dimensions have not been established. */
    MissingIdentity,

    /** The observed artifact belongs to a different engine. */
    Engine,

    /** This connector does not support the observed Heartbeat/facade build. */
    Heartbeat,

    /** This policy does not support the observed connector build. */
    Connector,

    /** Runtime version is absent or malformed; this is not proof of incompatibility. */
    UnknownRuntimeVersion,

    /** Runtime is outside the connector's documented hard range. */
    RuntimeRange,

    /** Runtime is on the connector's documented denylist. */
    DeniedRuntime,

    /** No supported negotiated protocol version. */
    Protocol,

    /** Configuration requires an unsupported schema/migration. */
    ConfigurationSchema,

    /** History requires an unsupported schema/migration. */
    HistorySchema,

    /** The artifact cannot run on the actual OS/architecture. */
    NativeTarget,

    /** The safe baseline probe has not completed. */
    MissingBaseline,

    /** The baseline probe positively failed. */
    BaselineRejected,

    /** Provenance/checksum of the actual artifact has not been established. */
    MissingProvenance,

    /** Provenance/checksum of the actual artifact failed verification. */
    ProvenanceRejected,

    /** Negotiated capabilities are not known. */
    MissingCapabilities,

    /** A capability required for safe operation is absent. */
    BaselineCapability,
}

/**
 * A decision for the supplied evidence, never a cached permit to start another process. Only Verified candidates
 * may be selected for automatic Managed updates. Unverified permits the proven baseline, with optional features
 * resolved separately. Unknown permits only a restricted probe; Incompatible forbids creation and resume.
 */
public data class EngineCompatibilityDecision(
    val status: Compatibility,
    val problems: Set<CompatibilityProblem> = emptySet(),
    val stamp: EngineCompatibilityStamp? = null,
) {
    init {
        if (status == Compatibility.Verified || status == Compatibility.Unverified) {
            require(stamp != null && stamp.identity().completeStamp() != null && problems.isEmpty()) {
                "Admission requires complete identity without problems"
            }
        } else {
            require(problems.isNotEmpty()) { "A blocked decision needs a reason" }
        }
    }

    /** Baseline creation/resume is admissible; route auth, trust and history checks remain mandatory. */
    val isBaselineAllowed: Boolean get() = status == Compatibility.Verified || status == Compatibility.Unverified

    /** The exact candidate passed a regression and may be considered for an automatic Managed update. */
    val isAutomaticUpdateAllowed: Boolean get() = status == Compatibility.Verified

    override fun toString(): String = "EngineCompatibilityDecision(status=$status, problems=$problems)"
}

/**
 * Pure assessment of a connector's observed bundle. Hard failures dominate missing facts. A new version is not
 * rejected merely because it is absent from [EngineCompatibilityRequirements.verified]. Missing facts never
 * yield Unverified or Verified, and a matching regression stamp never overrides a failed live baseline.
 */
public fun assessEngineCompatibility(
    requirements: EngineCompatibilityRequirements,
    evidence: EngineCompatibilityEvidence,
): EngineCompatibilityDecision {
    val rejected = mutableSetOf<CompatibilityProblem>()
    val missing = mutableSetOf<CompatibilityProblem>()
    val identity = evidence.identity
    checkIdentity(requirements, identity, rejected, missing)
    val stamp = identity.completeStamp()
    when (evidence.isBaselineConfirmed) {
        null -> missing += CompatibilityProblem.MissingBaseline
        false -> rejected += CompatibilityProblem.BaselineRejected
        true -> Unit
    }
    when (evidence.isProvenanceConfirmed) {
        null -> missing += CompatibilityProblem.MissingProvenance
        false -> rejected += CompatibilityProblem.ProvenanceRejected
        true -> Unit
    }
    val capabilities = evidence.capabilities
    if (capabilities == null) {
        missing += CompatibilityProblem.MissingCapabilities
    } else if (!capabilities.containsAll(requirements.baselineCapabilities)) {
        rejected += CompatibilityProblem.BaselineCapability
    }
    val status = when {
        rejected.isNotEmpty() -> Compatibility.Incompatible
        missing.isNotEmpty() -> Compatibility.Unknown
        stamp in requirements.verified -> Compatibility.Verified
        else -> Compatibility.Unverified
    }
    return EngineCompatibilityDecision(status, rejected + missing, stamp)
}

private fun checkIdentity(
    requirements: EngineCompatibilityRequirements,
    identity: EngineCompatibilityIdentity,
    rejected: MutableSet<CompatibilityProblem>,
    missing: MutableSet<CompatibilityProblem>,
) {
    fun <T> checkFact(actual: T?, accepted: Set<T>, problem: CompatibilityProblem) {
        when {
            actual == null || (actual is String && actual.isBlank()) -> missing += CompatibilityProblem.MissingIdentity
            actual !in accepted -> rejected += problem
        }
    }
    checkFact(identity.engine, setOf(requirements.engine), CompatibilityProblem.Engine)
    checkFact(identity.heartbeat, requirements.heartbeatVersions, CompatibilityProblem.Heartbeat)
    checkFact(identity.connector, requirements.connectorVersions, CompatibilityProblem.Connector)
    checkFact(identity.protocol, requirements.protocols, CompatibilityProblem.Protocol)
    checkFact(identity.configurationSchema, requirements.configurationSchemas, CompatibilityProblem.ConfigurationSchema)
    checkFact(identity.historySchema, requirements.historySchemas, CompatibilityProblem.HistorySchema)
    checkFact(identity.nativeTarget, requirements.nativeTargets, CompatibilityProblem.NativeTarget)
    if (identity.runtimeFingerprint.isNullOrBlank()) missing += CompatibilityProblem.MissingIdentity
    val version = identity.runtime?.let(CompatibilityVersion::parse)
    if (version == null) {
        missing += CompatibilityProblem.UnknownRuntimeVersion
    } else {
        val minimum = requireNotNull(CompatibilityVersion.parse(requirements.minimumRuntime))
        val maximum = requirements.maximumRuntimeExclusive?.let(CompatibilityVersion::parse)
        if (version < minimum || (maximum != null && version >= maximum)) rejected += CompatibilityProblem.RuntimeRange
        if (requirements.deniedRuntimeVersions.any { CompatibilityVersion.parse(it) == version }) {
            rejected += CompatibilityProblem.DeniedRuntime
        }
    }
}

private fun EngineCompatibilityIdentity.completeStamp(): EngineCompatibilityStamp? {
    val values = listOf(
        engine, heartbeat, connector, runtime, protocol, configurationSchema, historySchema,
        nativeTarget, runtimeFingerprint,
    )
    val isIncomplete = values.any { it == null || (it is String && it.isBlank()) }
    if (isIncomplete || runtime?.let(CompatibilityVersion::parse) == null) {
        return null
    }
    return EngineCompatibilityStamp(
        requireNotNull(engine), requireNotNull(heartbeat), requireNotNull(connector), runtime,
        requireNotNull(protocol), requireNotNull(configurationSchema), requireNotNull(historySchema),
        requireNotNull(nativeTarget), requireNotNull(runtimeFingerprint),
    )
}

/** Strict SemVer precedence, with numeric components kept as strings to avoid overflow from untrusted input. */
private data class CompatibilityVersion(val numbers: List<String>, val prerelease: List<String>) :
    Comparable<CompatibilityVersion> {
    override fun compareTo(other: CompatibilityVersion): Int =
        numbers.zip(other.numbers).map { (left, right) -> compareNumeric(left, right) }.firstOrNull { it != 0 }
            ?: comparePrerelease(prerelease, other.prerelease)

    companion object {
        private val pattern = Regex(
            """(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""" +
                """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
        )

        fun parse(value: String): CompatibilityVersion? {
            if (value.length > MAX_VERSION_CHARS) return null
            val match = pattern.matchEntire(value) ?: return null
            val pre = match.groupValues[PRERELEASE_GROUP].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return CompatibilityVersion(match.groupValues.subList(1, PRERELEASE_GROUP), pre)
        }
    }
}

private fun compareNumeric(left: String, right: String): Int =
    left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)

private fun comparePrerelease(left: List<String>, right: List<String>): Int {
    if (left.isEmpty() || right.isEmpty()) {
        return (if (left.isEmpty()) 1 else 0).compareTo(if (right.isEmpty()) 1 else 0)
    }
    return left.zip(right).map { (a, b) -> compareIdentifier(a, b) }.firstOrNull { it != 0 }
        ?: left.size.compareTo(right.size)
}

private fun compareIdentifier(left: String, right: String): Int {
    val isLeftNumeric = left.all(Char::isDigit)
    val isRightNumeric = right.all(Char::isDigit)
    return when {
        isLeftNumeric && isRightNumeric -> compareNumeric(left, right)
        isLeftNumeric != isRightNumeric -> if (isLeftNumeric) -1 else 1
        else -> left.compareTo(right)
    }
}

private const val MAX_VERSION_CHARS = 256
private const val PRERELEASE_GROUP = 4
