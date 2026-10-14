package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * The desktop host uses WINDOW Compose layers. Their normal scrim is drawn below native children,
 * and they cannot intercept input outside their own window. Cover the whole client area with the
 * owned layer instead. The upstream transition must not shrink or move this input barrier.
 * Native clipping starts afresh in this layer; a transcript's viewport belongs to its original window.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun HbPlatformDialog(
    onDismissRequest: () -> Unit,
    hasOverlay: Boolean,
    content: @Composable () -> Unit,
) {
    val owner = LocalAwtWindow.current
    if (hasOverlay && owner != null) {
        HbOwnedDialog(owner, onDismissRequest, content)
    } else {
        HbLayerDialog(onDismissRequest, content)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HbLayerDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest,
        DialogProperties(
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            scrimColor = Color.Transparent,
            animateTransition = false,
        ),
    ) {
        CompositionLocalProvider(
            LocalHbNativeContentViewport provides null,
            LocalHbDialogDismissRequest provides onDismissRequest,
        ) {
            HbDesktopDialogContent(onDismissRequest, content = content)
        }
    }
}

/** Pointer-only barriers add neither accessibility actions nor keyboard focus stops. */
@Composable
internal fun HbDesktopDialogContent(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .background(HbTheme.colors.scrim)
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
        )
        Box(
            Modifier.pointerInput(Unit) {
                // Empty card space must not activate the backdrop; child controls handle their own taps.
                detectTapGestures { }
            },
        ) {
            content()
        }
    }
}
