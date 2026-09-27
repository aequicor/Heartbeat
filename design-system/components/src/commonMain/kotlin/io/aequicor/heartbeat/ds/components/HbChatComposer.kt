package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/ChatComposer")

/**
 * Controlled multiline editor and send/stop action. Ctrl/Cmd+Enter (including numpad Enter) sends a
 * non-blank draft and never inserts a newline. Enter alone inserts a newline; Tab moves focus.
 * Sending, cancellation and clearing remain caller responsibilities.
 * Constrain [inputMaxHeight] to the minimum composer height in short viewports to preserve history space.
 * [leadingContent] and [trailingContent] populate the bottom toolbar; overflowing controls scroll
 * independently of the send action. Menus in either slot open above the complete editor.
 */
@Composable
public fun HbChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendLabel: String,
    stopLabel: String,
    modifier: Modifier = Modifier,
    inputMaxHeight: Dp = HbTheme.dimensions.composerMaxHeight,
    placeholder: String = "",
    isStreaming: Boolean = false,
    enabled: Boolean = true,
    accessibleLabel: String = placeholder,
    leadingContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val isSendEnabled = enabled && !isStreaming && value.isNotBlank()
    val actionIcon = if (isStreaming) HbIcons.Stop else HbIcons.ArrowUp
    val actionLabel = if (isStreaming) stopLabel else sendLabel
    val isActionEnabled = if (isStreaming) enabled else isSendEnabled
    val anchor = remember { mutableStateOf<IntRect?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    CompositionLocalProvider(LocalComposerAnchor provides anchor) {
        HbGlassPanel(
            modifier = modifier
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    anchor.value = IntRect(bounds.topLeft.round(), bounds.bottomRight.round())
                },
        ) {
            HbCard(
                modifier = Modifier.fillMaxWidth().hbFocusOutline(isFocused, HbTheme.shapes.large),
                contentPadding = HbTheme.spacing.l,
            ) {
                ComposerEditor(
                    value = value,
                    onValueChange = onValueChange,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = minOf(HbTheme.dimensions.composerEditorMinHeight, inputMaxHeight),
                            max = inputMaxHeight,
                        )
                        .onPreviewKeyEvent { event ->
                            handleSendShortcut(event, isSendEnabled, onSend) ||
                                handleFocusTraversal(event, focusManager)
                        },
                    placeholder = placeholder,
                    enabled = enabled,
                    accessibleLabel = accessibleLabel,
                )
                ComposerToolbar(leadingContent = leadingContent, trailingContent = trailingContent) {
                    ComposerIconButton(
                        icon = actionIcon,
                        label = actionLabel,
                        onClick = { submitComposerAction(isStreaming, onSend, onStop) },
                        enabled = isActionEnabled,
                        isPrimary = !isStreaming,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerToolbar(
    leadingContent: @Composable RowScope.() -> Unit,
    trailingContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    HbRow(modifier = modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbRow(
            modifier = Modifier.weight(1f).hbHorizontalScroll(rememberScrollState()),
            gap = HbTheme.spacing.xs,
            content = leadingContent,
        )
        Box(modifier = Modifier.weight(2f), contentAlignment = Alignment.CenterEnd) {
            HbRow(
                modifier = Modifier.hbHorizontalScroll(rememberScrollState(), reverseScrolling = true),
                gap = HbTheme.spacing.xs,
                content = trailingContent,
            )
        }
        content()
    }
}

@Composable
private fun ComposerEditor(
    value: String,
    onValueChange: (String) -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    accessibleLabel: String = placeholder,
) {
    HbEditableText(
        value = value,
        onValueChange = {
            log.d { "composer draft changed length=${it.length}" }
            onValueChange(it)
        },
        modifier = modifier.semantics { if (accessibleLabel.isNotBlank()) contentDescription = accessibleLabel },
        enabled = enabled,
        singleLine = false,
        interactionSource = interactionSource,
        placeholder = placeholder,
    )
}

/** Ctrl/Cmd+Enter is always consumed so a disabled send never falls through to the editor's newline. */
private fun handleSendShortcut(event: KeyEvent, isSendEnabled: Boolean, onSend: () -> Unit): Boolean {
    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (!isEnter || !(event.isCtrlPressed || event.isMetaPressed)) return false
    if (event.type != KeyEventType.KeyDown) return true
    if (isSendEnabled) {
        log.i { "send shortcut activated" }
        onSend()
    } else {
        log.d { "send shortcut ignored while sending is unavailable" }
    }
    return true
}

/** Tab leaves the multiline editor instead of inserting a tab character, keeping toolbar actions reachable. */
private fun handleFocusTraversal(event: KeyEvent, focusManager: FocusManager): Boolean {
    val hasCommandModifier = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed
    if (event.key != Key.Tab || hasCommandModifier) return false
    if (event.type == KeyEventType.KeyDown) {
        focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
    }
    return true
}

private fun submitComposerAction(isStreaming: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    log.i { "composer action streaming=$isStreaming" }
    if (isStreaming) onStop() else onSend()
}
