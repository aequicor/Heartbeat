package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import io.aequicor.heartbeat.core.logging.Log
import java.awt.Dialog
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.RootPaneContainer

/**
 * An edge-to-edge page needs a real window owner for its overlay. Compose WINDOW layers all belong to the root
 * container, so a Popup inside a layer dialog becomes its sibling and native page focus can cover it. This owned
 * modeless dialog covers only the owner's client area; its Close Popup is now a native child of this exact dialog.
 * The existing pointer barrier remains modal, and no window is made globally always-on-top.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HbOwnedDialog(owner: Window, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    key(owner) {
        val host = remember(owner) { HbOwnedDialogHost(owner) }
        SwingDialog(
            create = { host.create { dismiss() } },
            dispose = host::dispose,
        ) {
            // addNotify starts the first composition before the owner is showing. A nested WINDOW Popup
            // created then can stay at 1x1 without another native layout; wait for this window's first show.
            if (host.hasOpened) {
                CompositionLocalProvider(
                    LocalHbNativeContentViewport provides null,
                    LocalHbDialogDismissRequest provides onDismissRequest,
                ) {
                    HbDesktopDialogContent(onDismissRequest, content = content)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class HbOwnedDialogHost(private val owner: Window) {
    private val log = Log.tag("DS/OwnedDialog")
    private var placement: HbDialogWindowPlacement? = null
    private var isClosed = false
    var hasOpened by mutableStateOf(false)
        private set

    fun create(onDismissRequest: () -> Unit): ComposeDialog {
        val dialog = ComposeDialog(owner, Dialog.ModalityType.MODELESS, owner.graphicsConfiguration).apply {
            isUndecorated = true
            isTransparent = true
            isResizable = false
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) {
                    if (!isClosed && isShowing) hasOpened = true
                }

                override fun windowClosing(event: WindowEvent) {
                    if (isDisplayable) onDismissRequest()
                }
            })
        }
        val client = (owner as? RootPaneContainer)?.contentPane ?: owner
        placement = HbDialogWindowPlacement(owner, client) { dialog.bounds = it }.also { it.install() }
        log.v { "owned dialog opened" }
        return dialog
    }

    fun dispose(dialog: ComposeDialog) {
        isClosed = true
        placement?.close()
        placement = null
        dialog.dispose()
        log.v { "owned dialog closed" }
    }
}
