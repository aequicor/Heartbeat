package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserModel
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenError
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_address
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_back
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_disabled
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_disabled_hint
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_empty
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_empty_hint
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_engine_unavailable
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_forward
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_invalid_address
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_load_failed
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_loading
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_open
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_preparing
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_reload
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_stop
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_unsupported_address
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun BrowserScreen(model: BrowserModel, modifier: Modifier = Modifier) {
    val state by produceState(BrowserScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    BrowserScreenContent(state, model.store::intent, modifier) {
        BrowserWebView(model.surface, Modifier.fillMaxSize())
    }
}

/** Flat browser workspace. The native slot is composed only while the feature is enabled. */
@Composable
internal fun BrowserScreenContent(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
    browser: @Composable () -> Unit = {},
) {
    HbColumn(modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("browser")) {
        BrowserToolbar(state, onIntent)
        if (state.title.isNotEmpty()) {
            HbText(
                state.title,
                Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m),
                style = HbTheme.typography.caption,
                maxLines = 1,
            )
        }
        state.error?.let { HbBanner(stringResource(it.message()), Modifier.testTag("browser-error")) }
        if (state.isLoading) {
            HbLoadingState(stringResource(Res.string.browser_loading), Modifier.testTag("browser-loading"))
        }
        BrowserViewport(state, Modifier.weight(1f).fillMaxWidth(), browser)
    }
}

@Composable
private fun BrowserViewport(
    state: BrowserScreenState,
    modifier: Modifier = Modifier,
    browser: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state.phase) {
            BrowserPhase.Preparing -> HbLoadingState(stringResource(Res.string.browser_preparing))

            BrowserPhase.Disabled -> HbEmptyState(
                stringResource(Res.string.browser_disabled),
                description = stringResource(Res.string.browser_disabled_hint),
            )

            BrowserPhase.Ready -> {
                if (state.url.isNotEmpty()) browser()
                if (state.url.isEmpty() && state.error == null) {
                    HbEmptyState(
                        stringResource(Res.string.browser_empty),
                        description = stringResource(Res.string.browser_empty_hint),
                    )
                }
            }
        }
    }
}

private fun BrowserScreenError.message(): StringResource = when (this) {
    BrowserScreenError.InvalidAddress -> Res.string.browser_invalid_address
    BrowserScreenError.LoadFailed -> Res.string.browser_load_failed
    BrowserScreenError.UnsupportedAddress -> Res.string.browser_unsupported_address
    BrowserScreenError.EngineUnavailable -> Res.string.browser_engine_unavailable
}

@Composable
private fun BrowserAddressField(
    address: String,
    isEnabled: Boolean,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbTextField(
        value = address,
        onValueChange = { onIntent(BrowserScreenIntent.AddressChanged(it)) },
        modifier = modifier.testTag("browser-address").onPreviewKeyEvent {
            if (isEnabled && it.key == Key.Enter && it.type == KeyEventType.KeyUp) {
                onIntent(BrowserScreenIntent.Open)
                true
            } else {
                false
            }
        },
        placeholder = stringResource(Res.string.browser_address),
        enabled = isEnabled,
    )
}

@Composable
private fun BrowserToolbar(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEnabled = state.phase == BrowserPhase.Ready
    HbRow(modifier.fillMaxWidth().padding(HbTheme.spacing.s), gap = HbTheme.spacing.xs) {
        HbIconButton(
            HbIcons.ArrowLeft,
            stringResource(Res.string.browser_back),
            { onIntent(BrowserScreenIntent.Back) },
            Modifier.testTag("browser-back"),
            enabled = isEnabled && state.isBackAvailable,
        )
        HbIconButton(
            HbIcons.ArrowRight,
            stringResource(Res.string.browser_forward),
            { onIntent(BrowserScreenIntent.Forward) },
            Modifier.testTag("browser-forward"),
            enabled = isEnabled && state.isForwardAvailable,
        )
        BrowserAddressField(state.address, isEnabled, onIntent, Modifier.weight(1f))
        HbIconButton(
            HbIcons.ArrowRight,
            stringResource(Res.string.browser_open),
            { onIntent(BrowserScreenIntent.Open) },
            Modifier.testTag("browser-open"),
            enabled = isEnabled,
        )
        HbIconButton(
            if (state.isLoading) HbIcons.Stop else HbIcons.Refresh,
            stringResource(if (state.isLoading) Res.string.browser_stop else Res.string.browser_reload),
            { onIntent(if (state.isLoading) BrowserScreenIntent.Stop else BrowserScreenIntent.Reload) },
            Modifier.testTag("browser-reload-stop"),
            enabled = isEnabled && state.url.isNotEmpty(),
        )
    }
}
