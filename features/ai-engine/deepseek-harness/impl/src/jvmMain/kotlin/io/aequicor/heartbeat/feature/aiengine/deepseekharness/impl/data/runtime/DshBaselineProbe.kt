package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import kotlin.time.TimeMark

/** Outcome of one restricted baseline check: confirmed by observation, conclusively failed, or never observed. */
internal enum class DshBaselineOutcome { Confirmed, Rejected, Unobserved }

/**
 * What the restricted ACP `initialize` inspect observed of the selected artifact, plus the runtime line its own
 * `--version` reports. DSH's ACP `agentInfo.version` is adapter metadata, not the runtime line, so it is never used
 * here. An unobserved check is never a pass. No credential, user home, project configuration, tool, history write or
 * model prompt is involved.
 */
internal data class DshBaselineReceipt(
    val initialize: DshBaselineOutcome,
    /** Runtime line the selected launcher reports with `--version` under the isolated probe environment. */
    val runtimeVersion: String? = null,
) {
    val hasRejectedCheck: Boolean get() = initialize == DshBaselineOutcome.Rejected
}

/**
 * Restricted baseline of one exact selected artifact. It reuses the adapter's existing ACP `initialize` inspect
 * (isolated temporary home/work, cleared environment, no login, no `session/new`, no model prompt) and never starts
 * a second process kind. A conclusive failure rejects the baseline; a timeout or an otherwise unobserved check
 * stays inconclusive.
 */
internal fun interface DshBaselineProbe {
    suspend fun run(installation: Installation, deadline: TimeMark): DshBaselineReceipt
}
