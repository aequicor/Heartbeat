package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.browserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.isBrowserMetaShortcut
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
        BrowserWebView(model.surface.forTab(state.selectedTabId), Modifier.fillMaxSize())
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
    HbColumn(
        modifier.fillMaxSize().safeDrawingPadding()
            .background(HbTheme.surfaces.backdrop).testTag("browser")
            .onPreviewKeyEvent { event ->
                val shortcut = event.browserShortcut().takeIf { state.hasTabs && event.type == KeyEventType.KeyDown }
                val allowed = shortcut.takeUnless { it is BrowserShortcut.Zoom && !state.isZoomEnabled }
                allowed?.let { onIntent(BrowserScreenIntent.Shortcut(it)) } != null
            },
        gap = HbTheme.spacing.none,
    ) {
        BrowserTabs(state, onIntent)
        BrowserToolbar(state, onIntent)
        // Desktop viewports show the runtime failure with its own recovery action and download diagnostics.
        state.error?.takeUnless {
            (state.failure != null && it == BrowserScreenError.LoadFailed) ||
                (state.hasTabs && it == BrowserScreenError.EngineUnavailable)
        }?.let {
            HbBanner(stringResource(it.message()), Modifier.testTag("browser-error"))
        }
        if (state.isLoading && !state.hasTabs) {
            HbLoadingState(
                stringResource(Res.string.browser_loading),
                Modifier.testTag("browser-loading"),
            )
        }
        BrowserViewport(state, onIntent, Modifier.weight(1f).fillMaxWidth(), browser)
    }
}

/** The tab key this event presses, with the host's modifier conventions. */
internal fun KeyEvent.browserShortcut(): BrowserShortcut? {
    val isMeta = isBrowserMetaShortcut()
    val character = when (key) {
        Key.T -> 't'
        Key.W -> 'w'
        Key.Tab -> '\t'
        Key.Equals, Key.Plus, Key.NumPadAdd -> '+'
        Key.Minus, Key.NumPadSubtract -> '-'
        Key.Zero, Key.NumPad0 -> '0'
        in DIGIT_KEYS -> '1' + DIGIT_KEYS.indexOf(key)
        else -> return null
    }
    return browserShortcut(
        character,
        isAccelerator = if (isMeta) isMetaPressed && !isCtrlPressed else isCtrlPressed && !isMetaPressed,
        isControl = isCtrlPressed,
        isShift = isShiftPressed,
        isAlt = isAltPressed,
    )
}

private val DIGIT_KEYS =
    listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

@Composable
private fun BrowserViewport(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
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
                if (state.failure != null) {
                    BrowserLoadErrorPage(state, onIntent)
                } else if (state.url.isNotEmpty() || state.error == BrowserScreenError.EngineUnavailable) {
                    browser()
                }
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
    state: BrowserScreenState,
    isEnabled: Boolean,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbTextField(
        value = state.address,
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
        selectAllOnFocus = true,
        shape = CircleShape,
        leadingContent = { BrowserSecurityIndicator(state.security) },
        trailingContent = {
            HbRow(gap = HbTheme.spacing.none) {
                BrowserZoomControl(state, onIntent)
                HbIconButton(
                    HbIcons.ArrowRight,
                    stringResource(Res.string.browser_open),
                    { onIntent(BrowserScreenIntent.Open) },
                    Modifier.testTag("browser-open"),
                    enabled = isEnabled,
                )
            }
        },
    )
}

@Composable
private fun BrowserToolbar(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEnabled = state.phase == BrowserPhase.Ready
    HbRow(
        modifier.fillMaxWidth().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.s),
        gap = HbTheme.spacing.xs,
    ) {
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
        HbIconButton(
            if (state.isLoading) HbIcons.Stop else HbIcons.Refresh,
            stringResource(
                if (state.isLoading) Res.string.browser_stop else Res.string.browser_reload,
            ),
            {
                onIntent(
                    if (state.isLoading) BrowserScreenIntent.Stop else BrowserScreenIntent.Reload,
                )
            },
            Modifier.testTag("browser-reload-stop"),
            enabled = isEnabled && (state.url.isNotEmpty() || state.error == BrowserScreenError.EngineUnavailable),
        )
        BrowserAddressField(state, isEnabled, onIntent, Modifier.weight(1f))
    }
}
