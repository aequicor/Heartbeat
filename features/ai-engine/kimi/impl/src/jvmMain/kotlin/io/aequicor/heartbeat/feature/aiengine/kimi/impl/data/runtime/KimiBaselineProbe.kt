package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext

/** Outcome of one restricted baseline check: confirmed by observation, conclusively failed, or never observed. */
internal enum class KimiBaselineOutcome { Confirmed, Rejected, Unobserved }

/**
 * What the restricted ACP `initialize` inspect observed of the selected artifact. An unobserved check is never a
 * pass, and [runtimeVersion] is the version the process itself announced in the handshake, never a `--version`
 * claim. No credential, user home, project configuration, tool, history write or model prompt is involved.
 */
internal data class KimiBaselineReceipt(
    val initialize: KimiBaselineOutcome,
    val permissionGate: KimiBaselineOutcome,
    val hostedCallBinding: KimiBaselineOutcome,
    /** Runtime version the probed process reported in its own ACP initialization; null when never observed. */
    val runtimeVersion: String? = null,
) {
    val hasRejectedCheck: Boolean
        get() = initialize == KimiBaselineOutcome.Rejected ||
            permissionGate == KimiBaselineOutcome.Rejected ||
            hostedCallBinding == KimiBaselineOutcome.Rejected
}

/**
 * Restricted baseline of one exact selected artifact. It reuses the adapter's existing ACP `initialize` inspect
 * (isolated temporary home/work, cleared environment, no login, no `session/new`, no model prompt) and never
 * starts a second process kind. A conclusive failure rejects the baseline; a timeout or an otherwise unobserved
 * check stays inconclusive.
 */
internal fun interface KimiBaselineProbe {
    suspend fun run(installation: Installation, launch: LaunchContext): KimiBaselineReceipt
}
