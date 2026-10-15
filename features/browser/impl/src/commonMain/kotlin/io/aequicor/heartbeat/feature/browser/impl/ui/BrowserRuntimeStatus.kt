package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserRuntimeProblem
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserRuntimeUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_preparing
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_downloading
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_downloading_percent
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_installing
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_invalid
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_network
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_retry
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_storage
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_runtime_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Shared bootstrap placeholder for browser tabs and isolated documents, before mounting their native host. */
@Composable
internal fun BrowserRuntimeStatus(state: BrowserRuntimeUi, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (state == BrowserRuntimeUi.Ready) return
    Box(
        modifier.background(HbTheme.colors.surface).testTag("browser-runtime-status"),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            BrowserRuntimeUi.Preparing -> HbLoadingState(stringResource(Res.string.browser_preparing))

            BrowserRuntimeUi.Installing -> HbLoadingState(stringResource(Res.string.browser_runtime_installing))

            is BrowserRuntimeUi.Downloading -> RuntimeDownload(state)

            is BrowserRuntimeUi.Failed -> HbEmptyState(
                stringResource(state.reason.message()),
                action = {
                    HbButton(
                        stringResource(Res.string.browser_runtime_retry),
                        onRetry,
                        Modifier.testTag("browser-runtime-retry"),
                        style = HbButtonStyle.Secondary,
                        size = HbButtonSize.Small,
                    )
                },
            )

            BrowserRuntimeUi.Ready -> Unit
        }
    }
}

@Composable
private fun RuntimeDownload(state: BrowserRuntimeUi.Downloading) {
    val label = state.percent?.let {
        stringResource(Res.string.browser_runtime_downloading_percent, it)
    } ?: stringResource(Res.string.browser_runtime_downloading, state.receivedMiB)
    HbColumn(
        Modifier.widthIn(max = HbTheme.dimensions.dialogMaxWidth).fillMaxWidth().padding(HbTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HbLoadingState(label)
        state.percent?.let {
            HbProgressBar(it / 100f, Modifier.testTag("browser-runtime-progress"), contentDescription = label)
        }
    }
}

private fun BrowserRuntimeProblem.message(): StringResource = when (this) {
    BrowserRuntimeProblem.Network -> Res.string.browser_runtime_network
    BrowserRuntimeProblem.Storage -> Res.string.browser_runtime_storage
    BrowserRuntimeProblem.InvalidRuntime -> Res.string.browser_runtime_invalid
    BrowserRuntimeProblem.Unavailable -> Res.string.browser_runtime_unavailable
}
