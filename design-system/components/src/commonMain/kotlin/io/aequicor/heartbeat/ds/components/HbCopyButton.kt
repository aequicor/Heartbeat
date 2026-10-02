package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val log = Log.tag("DS/Copy")

/**
 * Copies [text] to the clipboard as plain text and shows a check mark with [copiedDescription] until [text] changes.
 * A Foundation variant over [HbIconButton] on every kit: the clipboard and the quiet icon control behave the same on
 * Material, Fluent and macOS, so no kit adapter is needed. Disabled for blank text. The copied text is never logged.
 */
@Composable
public fun HbCopyButton(
    text: String,
    contentDescription: String,
    copiedDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var isCopied by remember(text) { mutableStateOf(false) }
    HbIconButton(
        icon = if (isCopied) HbIcons.Check else HbIcons.Copy,
        contentDescription = if (isCopied) copiedDescription else contentDescription,
        onClick = {
            log.i { "copy requested length=${text.length}" }
            scope.launch { isCopied = copyPlainText(clipboard, text) }
        },
        modifier = modifier,
        enabled = enabled && text.isNotBlank(),
    )
}

/** Puts [text] on the clipboard; false when the platform refused it. */
internal suspend fun copyPlainText(clipboard: Clipboard, text: String): Boolean = try {
    clipboard.setClipEntry(hbPlainTextClipEntry(text))
    true
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    log.e(error) { "copy failed" }
    false
}

@Preview
@Composable
private fun HbCopyButtonLightPreview() {
    HbTheme(darkTheme = false) { CopyButtons() }
}

@Preview
@Composable
private fun HbCopyButtonDarkPreview() {
    HbTheme(darkTheme = true) { CopyButtons() }
}

@Composable
private fun CopyButtons() {
    HbRow(gap = HbTheme.spacing.s) {
        HbCopyButton("claude auth login", "Copy command", "Command copied")
        HbCopyButton("", "Copy command", "Command copied")
        HbCopyButton("claude auth login", "Скопировать команду для входа в терминале", "Скопировано", enabled = false)
    }
}
