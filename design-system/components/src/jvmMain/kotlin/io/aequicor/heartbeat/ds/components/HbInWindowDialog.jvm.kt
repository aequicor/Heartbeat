package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import java.awt.Window
import javax.swing.RootPaneContainer

private val LocalHbInWindowDialogs = compositionLocalOf<InWindowDialogs?> { null }

/**
 * The window's modal portal: edge-to-edge dialogs share its existing Compose canvas and native window.
 * Place once around the application content. Other popups keep their normal rendering strategy.
 * [onActiveChange] lets the window defer global shortcuts while a modal owns keyboard input.
 */
@Composable
public fun HbInWindowDialogHost(
    window: Window,
    modifier: Modifier = Modifier,
    onActiveChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val dialogs = remember(window) { InWindowDialogs() }
    val active = dialogs.entries.lastOrNull()
    SideEffect { onActiveChange(active != null) }
    DisposableEffect(window, active) {
        val root = (window as RootPaneContainer).rootPane
        val previous = root.getClientProperty(HbNativeModalOwner.PROPERTY)
        root.putClientProperty(HbNativeModalOwner.PROPERTY, active?.owner)
        onDispose {
            if (root.getClientProperty(HbNativeModalOwner.PROPERTY) === active?.owner) {
                root.putClientProperty(HbNativeModalOwner.PROPERTY, previous)
            }
        }
    }
    CompositionLocalProvider(
        LocalHbInWindowDialogs provides dialogs,
        LocalHbActiveNativeModalOwner provides active?.owner,
    ) {
        HbDialogFocusLayer(active != null, content, modifier) {
            active?.render()
        }
    }
}

/** Returns false in previews or hosts without a window portal; they keep the normal dialog fallback. */
@Composable
internal fun hbInWindowDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit): Boolean {
    // A nested dialog belongs to this entry's composition; replacing it would dispose its own registration.
    if (LocalHbNativeModalOwner.current != null) return false
    val dialogs = LocalHbInWindowDialogs.current ?: return false
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val dismiss by rememberUpdatedState(onDismissRequest)
    val body by rememberUpdatedState(content)
    val owner = remember { HbNativeModalOwner() }
    val entry = remember(dialogs) {
        InWindowDialog(owner) {
            CompositionLocalProvider(locals) {
                CompositionLocalProvider(
                    LocalHbNativeContentViewport provides null,
                    LocalHbDialogDismissRequest provides dismiss,
                    LocalHbNativeModalOwner provides owner,
                    LocalHbActiveNativeModalOwner provides owner,
                ) {
                    HbDesktopDialogContent(dismiss, content = body)
                }
            }
        }
    }
    DisposableEffect(entry) {
        dialogs.entries.add(entry)
        onDispose { dialogs.entries.remove(entry) }
    }
    return true
}

private class InWindowDialogs {
    val entries = mutableStateListOf<InWindowDialog>()
}

private data class InWindowDialog(val owner: HbNativeModalOwner, val render: @Composable () -> Unit)
