package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.generated.EmbeddedEngineBuild

/**
 * Declared identity of the JVM contracts this embedded adapter implements, built from the generated build facts of
 * the running build instead of a guessed version. [WIRE_REVISION] and [CONFIGURATION_REVISION] identify real wire
 * and stored layouts, never app or SDK versions: an incompatible change needs a new revision plus a researched
 * migration, exactly like [KoogHistoryFormat]. Only desktop JVM targets run the in-process candidate, so no other
 * target receives these facts.
 */
internal object KoogJvmCompatibility {
    /** Provider HTTP wire/DTO revision implemented by this adapter. */
    const val WIRE_REVISION: String = "1"

    /** Revision of the profile's stored Koog connections; 0 is the current implicit layout. */
    const val CONFIGURATION_REVISION: Int = 0

    const val SDK_COMPLETION: String = "sdk.completion"
    const val SDK_STREAM_STOP: String = "sdk.stream-stop"
    const val SDK_UNTERMINATED_STREAM: String = "sdk.unterminated-stream"
    const val SDK_MALFORMED_STREAM: String = "sdk.malformed-stream"
    const val SDK_CANCELLATION: String = "sdk.cancellation"
    const val SESSION_CREATE_SEND: String = "session.create-send"
    const val SESSION_HISTORY_RESUME: String = "session.history-resume"
    const val SESSION_CANCELLATION: String = "session.cancellation"
    const val SESSION_RECOVERY: String = "session.recovery"
    const val SESSION_TOOL_ROUND: String = "session.tool-round"
    const val SESSION_MODEL_CHANGE: String = "session.model-change"
    const val WIRE_PROVIDER: String = "wire.provider"
    const val WIRE_INCOMPLETE: String = "wire.incomplete"
    const val WIRE_MALFORMED: String = "wire.malformed"

    /** Every capability the offline baseline has to confirm before an ordinary runtime may start. */
    val baselineCapabilities: Set<String> = setOf(
        SDK_COMPLETION,
        SDK_STREAM_STOP,
        SDK_UNTERMINATED_STREAM,
        SDK_MALFORMED_STREAM,
        SDK_CANCELLATION,
        SESSION_CREATE_SEND,
        SESSION_HISTORY_RESUME,
        SESSION_CANCELLATION,
        SESSION_RECOVERY,
        SESSION_TOOL_ROUND,
        SESSION_MODEL_CHANGE,
        WIRE_PROVIDER,
        WIRE_INCOMPLETE,
        WIRE_MALFORMED,
    )

    /** Desktop hosts run the embedded adapter; mobile keeps its explicit unsupported path. */
    val desktopTargets: Set<String> = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)

    val protocol: EngineProtocolIdentity = EngineProtocolIdentity.ProbedSchema("koog-http", WIRE_REVISION)

    private val wireCapabilities = setOf(WIRE_PROVIDER, WIRE_INCOMPLETE, WIRE_MALFORMED)

    /**
     * A new build is admissible only after a successful baseline: the set of verified regressions stays empty
     * until an actual regression is recorded, so a passing baseline yields unverified admission and never an
     * automatic Managed update.
     */
    val requirements: EngineCompatibilityRequirements = EngineCompatibilityRequirements(
        engine = KoogEngineId,
        heartbeatVersions = setOf(EmbeddedEngineBuild.compiledFacade),
        connectorVersions = setOf(EmbeddedEngineBuild.connector),
        minimumRuntime = EmbeddedEngineBuild.runtimeVersion,
        protocols = setOf(protocol),
        configurationSchemas = setOf(CONFIGURATION_REVISION),
        historySchemas = setOf(KoogHistoryFormat.LEGACY_REVISION, KoogHistoryFormat.CURRENT_REVISION),
        nativeTargets = desktopTargets,
        baselineCapabilities = baselineCapabilities,
    )

    /** Identity of the build that actually runs: generated facts, not a version read from another artifact. */
    fun identity(host: HostPlatform): EngineCompatibilityIdentity = EngineCompatibilityIdentity(
        engine = KoogEngineId,
        heartbeat = EmbeddedEngineBuild.compiledFacade,
        connector = EmbeddedEngineBuild.connector,
        runtime = EmbeddedEngineBuild.runtimeVersion,
        protocol = protocol,
        configurationSchema = CONFIGURATION_REVISION,
        historySchema = KoogHistoryFormat.CURRENT_REVISION,
        nativeTarget = host.name,
        runtimeFingerprint = EmbeddedEngineBuild.dependencies,
    )

    /**
     * Evidence of one restricted acquisition of [provider]. Confirmed rows prove the recorded capabilities; a
     * conclusive failure rejects the baseline, and anything unobserved stays inconclusive instead of becoming a
     * pass. Provenance is the build contract the running classes were compiled from, never another artifact.
     */
    fun evidence(
        host: HostPlatform,
        provider: KoogProvider,
        report: KoogSdkReport,
        native: KoogNativeReceipt?,
        isWireConfirmed: Boolean,
    ): EngineCompatibilityEvidence {
        val rows = report.receipts.filter { it.provider == provider }
        val confirmed = buildSet {
            rows.filter { it.outcome == KoogSdkOutcome.Confirmed }.forEach { add(capability(it.check)) }
            addAll(native.confirmedCapabilities())
            if (isWireConfirmed) addAll(wireCapabilities)
        }
        val isRejected = rows.any { it.outcome == KoogSdkOutcome.Rejected } ||
            native.hasRejectedCheck() || !isWireConfirmed
        return EngineCompatibilityEvidence(
            identity = identity(host),
            isBaselineConfirmed = when {
                isRejected -> false
                confirmed.containsAll(baselineCapabilities) -> true
                else -> null
            },
            isProvenanceConfirmed = true,
            capabilities = if (confirmed.containsAll(baselineCapabilities)) baselineCapabilities else null,
        )
    }

    private fun capability(check: KoogSdkCheck): String = when (check) {
        KoogSdkCheck.CompletionCodec -> SDK_COMPLETION
        KoogSdkCheck.StreamStop -> SDK_STREAM_STOP
        KoogSdkCheck.UnterminatedStream -> SDK_UNTERMINATED_STREAM
        KoogSdkCheck.MalformedStream -> SDK_MALFORMED_STREAM
        KoogSdkCheck.Cancellation -> SDK_CANCELLATION
    }
}

private fun KoogNativeReceipt?.hasRejectedCheck(): Boolean {
    val receipt = this ?: return false
    val checks = listOf(
        receipt.isCreateAndSendConfirmed,
        receipt.isHistoryAndResumeConfirmed,
        receipt.isCancellationConfirmed,
        receipt.isRecoveryConfirmed,
        receipt.isToolRoundConfirmed,
        receipt.isModelChangeConfirmed,
    )
    return checks.any { !it }
}

private fun KoogNativeReceipt?.confirmedCapabilities(): Set<String> {
    val receipt = this ?: return emptySet()
    return buildSet {
        if (receipt.isCreateAndSendConfirmed) add(KoogJvmCompatibility.SESSION_CREATE_SEND)
        if (receipt.isHistoryAndResumeConfirmed) add(KoogJvmCompatibility.SESSION_HISTORY_RESUME)
        if (receipt.isCancellationConfirmed) add(KoogJvmCompatibility.SESSION_CANCELLATION)
        if (receipt.isRecoveryConfirmed) add(KoogJvmCompatibility.SESSION_RECOVERY)
        if (receipt.isToolRoundConfirmed) add(KoogJvmCompatibility.SESSION_TOOL_ROUND)
        if (receipt.isModelChangeConfirmed) add(KoogJvmCompatibility.SESSION_MODEL_CHANGE)
    }
}

/** Binds the JVM policy where the generated embedded build facts exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmKoogCompatibility(private val platform: PlatformInfo) : KoogCompatibility {
    /** Facts describe desktop artifacts only, so an unexpected host keeps the explicit no-policy result. */
    override fun requirements(): EngineCompatibilityRequirements? =
        KoogJvmCompatibility.requirements.takeIf { platform.host.name in KoogJvmCompatibility.desktopTargets }
}
