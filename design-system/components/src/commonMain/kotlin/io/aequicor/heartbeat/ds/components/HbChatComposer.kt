package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/ChatComposer")

/** Chooses a conventional editor, an inline input, or a spacious panel with an integrated toolbar. */
public enum class HbComposerLayout { Stacked, Inline, Panel }

/**
 * Controlled multiline editor and send/stop action. Ctrl/Cmd+Enter (including numpad Enter) sends a
 * non-blank draft and never inserts a newline. On desktop Enter sends and Shift+Enter inserts a newline;
 * touch platforms retain multiline Enter. Tab moves focus.
 * Sending, cancellation and clearing remain caller responsibilities.
 * Constrain [inputMaxHeight] to the minimum composer height in short viewports to preserve history space.
 * [leadingContent] and [trailingContent] populate the bottom toolbar; overflowing controls scroll
 * independently of the send action. Menus in either slot open above the complete editor.
 * [layout] keeps the conventional stacked editor by default; [HbComposerLayout.Inline] pairs the
 * editor with its primary action in one rounded flat surface and places secondary controls below.
 * [HbComposerLayout.Panel] keeps the editor above one integrated toolbar at every window width.
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
    layout: HbComposerLayout = HbComposerLayout.Stacked,
    placeholder: String = "",
    isStreaming: Boolean = false,
    enabled: Boolean = true,
    accessibleLabel: String = placeholder,
    leadingContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val isEnterSendingEnabled = HbTheme.dimensions.isDesktop
    val isSendEnabled = enabled && !isStreaming && value.isNotBlank()
    val actionLabel = if (isStreaming) stopLabel else sendLabel
    val isActionEnabled = if (isStreaming) enabled else isSendEnabled
    val anchor = remember { mutableStateOf<IntRect?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val editorMinHeight = when (layout) {
        HbComposerLayout.Stacked -> HbTheme.dimensions.composerEditorMinHeight
        HbComposerLayout.Inline -> HbTheme.dimensions.controlHeight
        HbComposerLayout.Panel -> HbTheme.dimensions.controlHeight
    }
    CompositionLocalProvider(LocalComposerAnchor provides anchor) {
        Box(
            modifier = modifier
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    anchor.value = IntRect(bounds.topLeft.round(), bounds.bottomRight.round())
                },
        ) {
            ComposerLayout(
                layout = layout,
                isFocused = isFocused,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                action = {
                    ComposerPrimaryAction(
                        layout = layout,
                        isStreaming = isStreaming,
                        label = actionLabel,
                        onClick = { submitComposerAction(isStreaming, onSend, onStop) },
                        enabled = isActionEnabled,
                    )
                },
                editor = { editorModifier ->
                    ComposerEditor(
                        value = value,
                        onValueChange = onValueChange,
                        interactionSource = interactionSource,
                        modifier = editorModifier
                            .heightIn(min = minOf(editorMinHeight, inputMaxHeight), max = inputMaxHeight)
                            .onPreviewKeyEvent { event ->
                                handleSendShortcut(event, isSendEnabled, isEnterSendingEnabled, onSend) ||
                                    handleFocusTraversal(event, focusManager)
                            },
                        placeholder = placeholder,
                        enabled = enabled,
                        accessibleLabel = accessibleLabel,
                    )
                },
            )
        }
    }
}

@Composable
private fun ComposerPrimaryAction(
    layout: HbComposerLayout,
    isStreaming: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val isPanel = layout == HbComposerLayout.Panel
    ComposerIconButton(
        icon = if (isStreaming) HbIcons.Stop else HbIcons.ArrowUp,
        label = label,
        onClick = onClick,
        enabled = enabled,
        isPrimary = !isStreaming,
        shape = if (isPanel || layout == HbComposerLayout.Stacked) HbTheme.shapes.small else CircleShape,
        size = if (isPanel) HbTheme.dimensions.composerActionSize else HbTheme.dimensions.touchTarget,
        background = if (isPanel && enabled) HbTheme.surfaces.composerAction else null,
        tint = HbTheme.surfaces.onComposerAction.takeIf { isPanel && enabled },
        iconSize = HbTheme.dimensions.iconSize,
    )
}

@Composable
private fun ComposerLayout(
    layout: HbComposerLayout,
    isFocused: Boolean,
    leadingContent: @Composable RowScope.() -> Unit,
    trailingContent: @Composable RowScope.() -> Unit,
    action: @Composable () -> Unit,
    editor: @Composable (Modifier) -> Unit,
) {
    val currentEditor by rememberUpdatedState(editor)
    val currentAction by rememberUpdatedState(action)
    val currentLeading by rememberUpdatedState(leadingContent)
    val currentTrailing by rememberUpdatedState(trailingContent)
    val movableEditor = remember { movableContentOf { modifier: Modifier -> currentEditor(modifier) } }
    val movableAction = remember { movableContentOf { currentAction() } }
    val movableToolbar = remember {
        movableContentOf { modifier: Modifier, toolbarLayout: HbComposerLayout ->
            ComposerToolbar(
                currentLeading,
                currentTrailing,
                modifier,
                isBalanced = toolbarLayout == HbComposerLayout.Panel,
            ) {
                if (toolbarLayout != HbComposerLayout.Inline) movableAction()
            }
        }
    }
    when (layout) {
        HbComposerLayout.Stacked -> HbCard(
            modifier = Modifier.fillMaxWidth().hbFocusOutline(isFocused, HbTheme.shapes.large, isTextInput = true),
            contentPadding = HbTheme.spacing.l,
        ) {
            movableEditor(Modifier.fillMaxWidth())
            movableToolbar(Modifier, HbComposerLayout.Stacked)
        }

        HbComposerLayout.Inline -> {
            val shape = RoundedCornerShape(HbTheme.dimensions.cornerRadius)
            HbColumn(gap = HbTheme.spacing.xxs) {
                HbPanel(
                    modifier = Modifier.fillMaxWidth().hbFocusOutline(isFocused, shape, isTextInput = true),
                    shape = shape,
                ) {
                    HbRow(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
                        gap = HbTheme.spacing.s,
                    ) {
                        movableEditor(
                            Modifier.weight(1f).padding(start = HbTheme.spacing.m, top = HbTheme.spacing.s),
                        )
                        movableAction()
                    }
                }
                movableToolbar(Modifier.padding(horizontal = HbTheme.spacing.m), HbComposerLayout.Inline)
            }
        }

        HbComposerLayout.Panel -> ComposerPanelLayout(
            isFocused = isFocused,
            leadingContent = currentLeading,
            trailingContent = currentTrailing,
            action = movableAction,
            editor = movableEditor,
        )
    }
}

@Composable
private fun ComposerToolbar(
    leadingContent: @Composable RowScope.() -> Unit,
    trailingContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    isBalanced: Boolean = false,
    content: @Composable () -> Unit,
) {
    HbRow(modifier = modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbRow(
            modifier = Modifier.weight(1f).hbHorizontalScroll(rememberScrollState()),
            gap = HbTheme.spacing.xs,
            content = leadingContent,
        )
        Box(modifier = Modifier.weight(if (isBalanced) 1f else 2f), contentAlignment = Alignment.CenterEnd) {
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

/** Sending keys are consumed even when disabled; Shift+Enter remains an editor newline. */
private fun handleSendShortcut(
    event: KeyEvent,
    isSendEnabled: Boolean,
    isEnterSendingEnabled: Boolean,
    onSend: () -> Unit,
): Boolean {
    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
    val isSendKey = event.isCtrlPressed || event.isMetaPressed || (isEnterSendingEnabled && !event.isShiftPressed)
    if (!isEnter || !isSendKey || event.isAltPressed) return false
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
