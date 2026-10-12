package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.util.concurrent.CompletableFuture

internal enum class NativeBrowserMode { Panel, Expanded, Hidden }

internal class NativeBrowserContentState {
    var mode by mutableStateOf(NativeBrowserMode.Panel)
    var isDarkTheme by mutableStateOf(false)
    var isDialogVisible by mutableStateOf(false)
    var dialogText by mutableStateOf("")
    val dialogFocused = CompletableFuture<Unit>()
    val dialogTyped = CompletableFuture<String>()
    val dialogClosed = CompletableFuture<Unit>()
    val darkThemeDrawn = CompletableFuture<Unit>()
}

/** Production SwingPanel with test host geometry; this does not substitute for StudioBrowserLayout. */
@Composable
internal fun NativeComposeBrowserContent(fixture: NativeComposeBrowserFixture, state: NativeBrowserContentState) {
    HbTheme(darkTheme = state.isDarkTheme) {
        HbRow(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            if (state.mode != NativeBrowserMode.Expanded) Spacer(Modifier.weight(1f))
            if (state.mode != NativeBrowserMode.Hidden) {
                BrowserWebView(
                    fixture,
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
