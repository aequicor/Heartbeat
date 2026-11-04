package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.network.OfflineHttpProgress
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.generated.EmbeddedEngineBuild

internal enum class KoogSdkCheck { CompletionCodec, StreamStop, UnterminatedStream, MalformedStream, Cancellation }
internal enum class KoogSdkOutcome { Confirmed, Rejected, Unobserved }
internal enum class KoogSdkIssue { WireMismatch, Payload, Terminal, MalformedAccepted, Failure, Deadline, OwnedJobs }

/** Compiled inputs only. They neither authenticate the loaded JVM classpath nor identify the running host. */
internal data class KoogSdkBuildInputs(
    val compiledFacade: String,
    val connector: String,
    val runtimeVersion: String,
    val dependencies: String,
) {
    override fun toString(): String = "KoogSdkBuildInputs"
}

/** No prompt, credential, provider response, path or raw error is retained. */
internal data class KoogSdkReceipt(
    val provider: KoogProvider,
    val check: KoogSdkCheck,
    val outcome: KoogSdkOutcome,
    val issue: KoogSdkIssue? = null,
    val exchange: OfflineHttpProgress? = null,
    val isSdkCleanupConfirmed: Boolean? = null,
)

/**
 * SDK subcontracts, not a native-session baseline or admission permit. Even all Confirmed leaves host provenance,
 * configuration/history, create/attach/recovery, hooks/tools and native ownership to their own checks.
 */
internal data class KoogSdkReport(val build: KoogSdkBuildInputs, val receipts: List<KoogSdkReceipt>) {
    val hasRejectedChecks: Boolean get() = receipts.any { it.outcome == KoogSdkOutcome.Rejected }

    override fun toString(): String = "KoogSdkReport"
}

internal fun sdkBuildInputs(): KoogSdkBuildInputs = KoogSdkBuildInputs(
    EmbeddedEngineBuild.compiledFacade,
    EmbeddedEngineBuild.connector,
    EmbeddedEngineBuild.runtimeVersion,
    EmbeddedEngineBuild.dependencies,
)
