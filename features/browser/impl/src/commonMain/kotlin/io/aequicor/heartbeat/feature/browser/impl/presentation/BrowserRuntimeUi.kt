package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimeFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation

/** Technical preparation of a native surface, independent of page navigation and document contents. */
internal sealed interface BrowserRuntimeUi {
    data object Preparing : BrowserRuntimeUi
    data class Downloading(val receivedMiB: Long, val percent: Int?) : BrowserRuntimeUi
    data object Installing : BrowserRuntimeUi
    data object Ready : BrowserRuntimeUi
    data class Failed(val reason: BrowserRuntimeProblem) : BrowserRuntimeUi
}

internal enum class BrowserRuntimeProblem { Network, Storage, InvalidRuntime, Unavailable }

/** Coarse progress avoids recomposing the viewport for every network chunk; native startup follows installation. */
internal fun BrowserRuntimePreparation.toPresentation(): BrowserRuntimeUi = when (this) {
    BrowserRuntimePreparation.Idle, BrowserRuntimePreparation.Ready -> BrowserRuntimeUi.Preparing

    is BrowserRuntimePreparation.Downloading -> BrowserRuntimeUi.Downloading(
        receivedMiB = bytes.coerceAtLeast(0) / BYTES_PER_MIB,
        percent = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it * PERCENT).toInt().coerceIn(0, PERCENT) },
    )

    BrowserRuntimePreparation.Installing -> BrowserRuntimeUi.Installing

    is BrowserRuntimePreparation.Failed -> BrowserRuntimeUi.Failed(
        when (reason) {
            BrowserRuntimeFailure.Network -> BrowserRuntimeProblem.Network
            BrowserRuntimeFailure.Storage -> BrowserRuntimeProblem.Storage
            BrowserRuntimeFailure.InvalidRuntime -> BrowserRuntimeProblem.InvalidRuntime
            BrowserRuntimeFailure.Unavailable -> BrowserRuntimeProblem.Unavailable
        },
    )
}

/** Preserves the installer's safe diagnostic when native creation fails after awaiting the same attempt. */
internal fun BrowserRuntimeUi.failed(): BrowserRuntimeUi.Failed =
    this as? BrowserRuntimeUi.Failed ?: BrowserRuntimeUi.Failed(BrowserRuntimeProblem.Unavailable)

private const val BYTES_PER_MIB = 1024L * 1024
private const val PERCENT = 100
