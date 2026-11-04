package io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.CLAUDE_NATIVE_GATE_FLOOR
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeHost
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.claudeReleasePlatform
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.generated.ClaudeBuildContract
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity

/**
 * Declared identity of the Claude Code CLI contracts this connector implements, built from the generated facts of the
 * running build and the adapter's own host target instead of a guessed version. [WIRE_REVISION],
 * [ClaudeConfigurationFormat] and [ClaudeHistoryFormat] identify real wire/stored layouts; an incompatible change
 * needs a new revision plus a researched migration. Only desktop JVM targets run a Claude process, so no other target
 * receives these facts.
 */
internal object ClaudeJvmCompatibility {
    /** Stream-json JSONL wire/control revision implemented by this adapter; bump on any incompatible wire change. */
    const val WIRE_REVISION: String = "1"

    /** The selected CLI answers the stream-json initialize control request in an isolated session. */
    const val STREAM_INITIALIZE: String = "stream.initialize"

    /** The initialize answer carries the `models` catalog the adapter reads. */
    const val CATALOG_MODELS: String = "catalog.models"

    /** Every capability the login-free initialize handshake has to confirm before an ordinary runtime may start. */
    val baselineCapabilities: Set<String> = setOf(STREAM_INITIALIZE, CATALOG_MODELS)

    /** Desktop hosts run a Claude process; mobile keeps its explicit unsupported path. */
    val desktopTargets: Set<String> = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)

    /**
     * The Claude CLI has no negotiated stream-json version: this identifies the pinned control schema the baseline
     * probes. [WIRE_REVISION] changes only for an incompatible wire change, never for a Claude Code release.
     */
    val protocol: EngineProtocolIdentity = EngineProtocolIdentity.ProbedSchema("claude-cli-stream", WIRE_REVISION)

    /**
     * Policy of this build for the published release target [target] of [host]. The floor is the only version floor
     * the adapter documents and relies on: `supportsClaudeNativeGate` refuses older builds, and 2.1.285 passed the
     * live macOS initialize/hook/permission protocol probe. It is not an invented range and it is not a substitute
     * for the baseline: `verified` stays empty, so a passing probe admits the build as Unverified and never
     * authorizes an automatic Managed update.
     */
    fun requirements(host: HostPlatform, target: String?): EngineCompatibilityRequirements? {
        if (host.name !in desktopTargets || target == null) return null
        return EngineCompatibilityRequirements(
            engine = ClaudeEngine.Id,
            heartbeatVersions = setOf(ClaudeBuildContract.heartbeat),
            connectorVersions = setOf(ClaudeBuildContract.connector),
            minimumRuntime = CLAUDE_NATIVE_GATE_FLOOR,
            protocols = setOf(protocol),
            configurationSchemas = setOf(ClaudeConfigurationFormat.CURRENT_REVISION),
            historySchemas = setOf(ClaudeHistoryFormat.CURRENT_REVISION),
            nativeTargets = setOf(target),
            baselineCapabilities = baselineCapabilities,
        )
    }

    /**
     * Evidence of one restricted acquisition of [artifact]. Confirmed rows prove the recorded capabilities; a
     * conclusive failure rejects the baseline, and anything unobserved stays inconclusive instead of becoming a
     * pass. The runtime is the version the managed install store recorded for the exact executable whose digest was
     * checked, never a `--version` claim; promotion re-observes it from the ordinary process before the transfer.
     * Provenance is the recorded executable digest of the verified managed copy, never the release archive digest.
     */
    fun evidence(
        artifact: ClaudeArtifactFacts,
        baseline: ClaudeBaselineReceipt?,
        isProvenanceConfirmed: Boolean?,
    ): EngineCompatibilityEvidence {
        val confirmed = buildSet {
            if (baseline?.initialize == ClaudeBaselineOutcome.Confirmed) add(STREAM_INITIALIZE)
            if (baseline?.models == ClaudeBaselineOutcome.Confirmed) add(CATALOG_MODELS)
        }
        val isRejected = baseline?.hasRejectedCheck == true
        val isComplete = confirmed.containsAll(baselineCapabilities)
        return EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = ClaudeEngine.Id,
                heartbeat = ClaudeBuildContract.heartbeat,
                connector = ClaudeBuildContract.connector,
                runtime = artifact.runtime,
                protocol = protocol,
                configurationSchema = ClaudeConfigurationFormat.CURRENT_REVISION,
                historySchema = ClaudeHistoryFormat.CURRENT_REVISION,
                nativeTarget = artifact.nativeTarget,
                runtimeFingerprint = artifact.runtimeFingerprint,
            ),
            isBaselineConfirmed = when {
                isRejected -> false
                isComplete -> true
                else -> null
            },
            isProvenanceConfirmed = isProvenanceConfirmed,
            capabilities = if (isComplete) baselineCapabilities else null,
        )
    }
}

/** Non-secret identity of the exact selected artifact; null fields stay unestablished instead of being guessed. */
internal data class ClaudeArtifactFacts(
    val runtime: String?,
    val nativeTarget: String?,
    val runtimeFingerprint: String?,
)

/** Binds the JVM policy where the generated build facts and the host release target exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmClaudeCompatibility(platform: PlatformInfo, host: ClaudeHost = ClaudeHost()) : ClaudeCompatibility {
    private val policy = ClaudeJvmCompatibility.requirements(platform.host, claudeReleasePlatform(host))

    override fun requirements(): EngineCompatibilityRequirements? = policy
}
