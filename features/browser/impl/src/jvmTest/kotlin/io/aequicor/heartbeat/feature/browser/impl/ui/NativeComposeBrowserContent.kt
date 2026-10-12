package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Popup
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import java.util.concurrent.CompletableFuture

internal enum class NativeBrowserMode { Panel, Expanded, Hidden }

internal class NativeBrowserContentState {
    var mode by mutableStateOf(NativeBrowserMode.Panel)
    var isDarkTheme by mutableStateOf(false)
    var isDialogVisible by mutableStateOf(false)
    var isMenuVisible by mutableStateOf(false)
    var isSmallPopupVisible by mutableStateOf(false)
    var dialogText by mutableStateOf("")
    val dialogFocused = CompletableFuture<Unit>()
    val dialogTyped = CompletableFuture<String>()
    val dialogClosed = CompletableFuture<Unit>()
    val darkThemeDrawn = CompletableFuture<Unit>()
    val menuFocused = CompletableFuture<Unit>()
    val menuDrawn = CompletableFuture<Unit>()
    val menuSelected = CompletableFuture<String>()
    val smallPopupDrawn = CompletableFuture<Unit>()

    /** Last drawn popup surface in window-container pixels; written on the EDT. */
    @Volatile
    var popupBounds: Rect? = null
}

/** Production SwingPanel with test host geometry; this does not substitute for StudioBrowserLayout. */
@Composable
internal fun NativeComposeBrowserContent(fixture: NativeComposeBrowserFixture, state: NativeBrowserContentState) {
    HbTheme(darkTheme = state.isDarkTheme) {
        HbRow(Modifier.fillMaxSize().background(HbTheme.colors.background), gap = HbTheme.spacing.none) {
            if (state.mode != NativeBrowserMode.Expanded) Spacer(Modifier.weight(1f))
            if (state.mode != NativeBrowserMode.Hidden) {
                NativeBrowserPanel(
                    fixture,
                    state,
                    if (state.mode == NativeBrowserMode.Expanded) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.width(HbTheme.dimensions.settingsMaxWidth).fillMaxHeight()
                    },
                )
            }
        }
        if (state.isDialogVisible) NativeBrowserDialog(state)
    }
    LaunchedEffect(state.isDarkTheme) {
        if (state.isDarkTheme) {
            withFrameNanos { }
            withFrameNanos { }
            state.darkThemeDrawn.complete(Unit)
        }
    }
}

@Composable
private fun NativeBrowserPanel(
    fixture: NativeComposeBrowserFixture,
    state: NativeBrowserContentState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        BrowserWebView(fixture, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.Center).size(HbTheme.dimensions.touchTarget)) {
            HbMenu(
                items = persistentListOf(HbMenuItem("verify", "Native popup verification")),
                isExpanded = state.isMenuVisible,
                onDismiss = { state.isMenuVisible = false },
                onItem = { state.menuSelected.complete(it) },
                label = "Browser popup",
                modifier = Modifier.onFocusChanged {
                    if (it.hasFocus) state.menuFocused.complete(Unit)
                }.focusGroup().onGloballyPositioned {
                    state.popupBounds = it.boundsInWindow()
                }.drawWithContent {
                    drawContent()
                    state.menuDrawn.complete(Unit)
                },
            )
            if (state.isSmallPopupVisible) NativeSmallPopup(state)
        }
    }
}

/** An anchored non-focusable layer smaller than the window, like composer suggestions. */
@Composable
private fun NativeSmallPopup(state: NativeBrowserContentState) {
    Popup(alignment = Alignment.BottomStart) {
        Box(
            Modifier.size(HbTheme.dimensions.touchTarget * 3, HbTheme.dimensions.touchTarget)
                .background(HbTheme.colors.brand)
                .onGloballyPositioned { state.popupBounds = it.boundsInWindow() }
                .drawWithContent {
                    drawContent()
                    state.smallPopupDrawn.complete(Unit)
                },
        )
    }
}

@Composable
private fun NativeBrowserDialog(state: NativeBrowserContentState) {
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        onDispose { state.dialogClosed.complete(Unit) }
    }
    HbDialog(title = "Native browser verification", onDismissRequest = { state.isDialogVisible = false }) {
        HbTextField(
            value = state.dialogText,
            onValueChange = {
                state.dialogText = it
                state.dialogTyped.complete(it)
            },
            modifier = Modifier.focusRequester(focus).onFocusChanged {
                if (it.isFocused) state.dialogFocused.complete(Unit)
            },
        )
    }
    LaunchedEffect(focus) {
        // HbDialog first focuses its panel; then this test focuses its real input control.
        withFrameNanos { }
        withFrameNanos { }
        focus.requestFocus()
    }
}
