package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = Log.tag("DS/Copy")

/**
 * Copies [text] to the clipboard as plain text. A check mark with [copiedDescription], or an alert with
 * [failedDescription] when the platform refuses the clipboard, shows for [HbTheme.motion]'s copy feedback time; the
 * state is announced politely to screen readers. A Foundation variant over [HbIconButton] on every kit: the clipboard
 * and the quiet icon control behave the same on Material, Fluent and macOS, so no kit adapter is needed. Disabled for
 * blank text. The copied text is never logged.
 */
@Composable
public fun HbCopyButton(
    text: String,
    contentDescription: String,
    copiedDescription: String,
    failedDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var status by remember(text) { mutableStateOf(CopyStatus.Idle) }
    val feedbackMillis = HbTheme.motion.copyFeedbackMillis
    LaunchedEffect(text, status) {
        if (status == CopyStatus.Copied || status == CopyStatus.Failed) {
            delay(feedbackMillis.toLong())
            log.d { "copy feedback cleared" }
            status = CopyStatus.Idle
        }
    }
    val state = when (status) {
        CopyStatus.Copied -> copiedDescription
        CopyStatus.Failed -> failedDescription
        CopyStatus.Idle -> contentDescription
    }
    HbIconButton(
        icon = when (status) {
            CopyStatus.Copied -> HbIcons.Check
            CopyStatus.Failed -> HbIcons.Alert
            CopyStatus.Idle -> HbIcons.Copy
        },
        contentDescription = contentDescription,
        onClick = {
            log.i { "copy requested length=${text.length}" }
            scope.launch { status = if (copyPlainText(clipboard, text)) CopyStatus.Copied else CopyStatus.Failed }
        },
        modifier = modifier.semantics {
            stateDescription = state
            liveRegion = LiveRegionMode.Polite
        },
        enabled = enabled && text.isNotBlank(),
        tooltipText = state,
    )
}

private enum class CopyStatus { Idle, Copied, Failed }

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
        HbCopyButton("claude auth login", "Copy command", "Command copied", "Copy failed")
        HbCopyButton("", "Copy command", "Command copied", "Copy failed")
        HbCopyButton(
            "claude auth login",
            "Скопировать команду для входа в терминале",
            "Скопировано",
            "Не удалось скопировать",
            enabled = false,
        )
    }
}
