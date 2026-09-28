package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.shortcut_command
import io.aequicor.heartbeat.feature.aistudio.impl.resources.shortcut_control
import org.jetbrains.compose.resources.stringResource

private val shortcutLog = Log.tag("Studio/Shortcuts")

/** Native shortcut label; it follows the host, independently of the previewed control kit. */
@Composable
internal fun studioShortcutLabel(key: String): String = stringResource(
    if (isStudioMetaShortcut()) Res.string.shortcut_command else Res.string.shortcut_control,
    key,
)

/** The host accelerator modifier; a previewed control kit does not override native keyboard conventions. */
internal expect fun isStudioMetaShortcut(): Boolean

/** Composition-owned focus, retained while the sidebar is absent or moving between drawer and wide layout. */
@Stable
internal class StudioFocusState {
    val workspace = FocusRequester()
    val search = FocusRequester()
    var searchRequest by mutableIntStateOf(0)
    var isSidebarFocused by mutableStateOf(false)

    /** Keep the shortcut receiver alive when its focused sidebar child is about to leave composition. */
    fun beforeIntent(intent: AiStudioScreenIntent, state: AiStudioScreenState) {
        val isClosing = (intent == AiStudioScreenIntent.ToggleSidebar && state.sidebar.isVisible) ||
            (intent is AiStudioScreenIntent.SetDrawerOpen && !intent.isOpen) ||
            (intent == AiStudioScreenIntent.ToggleSearch && state.sidebar.isSearchVisible)
        if (isClosing && isSidebarFocused) workspace.requestFocus()
    }
}

/** Observes the entire sidebar group, including its search field and nested row actions. */
internal fun Modifier.studioSidebarFocus(focus: StudioFocusState): Modifier =
    onFocusChanged { focus.isSidebarFocused = it.hasFocus }.focusGroup()

/** Routes desktop accelerators through existing intents; ordinary editor keys are never consumed. */
@Composable
internal fun Modifier.studioShortcuts(
    state: AiStudioScreenState,
    isCompact: Boolean,
    focus: StudioFocusState,
    onIntent: (AiStudioScreenIntent) -> Unit,
): Modifier {
    val pressed = remember { mutableSetOf<Key>() }
    SideEffect(focus) { focus.workspace.requestFocus() }
    return onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent pressed.remove(event.key)
        val isShortcut = event.key == Key.N || event.key == Key.K || event.key == Key.Backslash
        if (event.type != KeyEventType.KeyDown || !event.hasStudioModifier() || !isShortcut) {
            return@onPreviewKeyEvent false
        }
        if (pressed.add(event.key)) {
            shortcutLog.i { "Studio shortcut activated" }
            dispatchStudioShortcut(event.key, state, isCompact, focus, onIntent)
        }
        true
    }.focusRequester(focus.workspace).onFocusChanged { if (!it.hasFocus) pressed.clear() }.focusable()
}

private fun KeyEvent.hasStudioModifier(): Boolean {
    val isHostModifier = if (isStudioMetaShortcut()) {
        isMetaPressed && !isCtrlPressed
    } else {
        isCtrlPressed && !isMetaPressed
    }
    return isHostModifier && !isAltPressed && !isShiftPressed
}

private fun dispatchStudioShortcut(
    key: Key,
    state: AiStudioScreenState,
    isCompact: Boolean,
    focus: StudioFocusState,
    onIntent: (AiStudioScreenIntent) -> Unit,
) {
    when (key) {
        Key.N -> onIntent(AiStudioScreenIntent.NewSession(state.sidebarInput().newSessionProjectId))

        Key.K -> focusSearch(state, isCompact, focus, onIntent)

        Key.Backslash -> onIntent(
            if (isCompact) {
                AiStudioScreenIntent.SetDrawerOpen(!state.sidebar.isDrawerOpen)
            } else {
                AiStudioScreenIntent.ToggleSidebar
            },
        )
    }
}

private fun focusSearch(
    state: AiStudioScreenState,
    isCompact: Boolean,
    focus: StudioFocusState,
    onIntent: (AiStudioScreenIntent) -> Unit,
) {
    val isAttached = if (isCompact) state.sidebar.isDrawerOpen else state.sidebar.isVisible
    if (isCompact && !state.sidebar.isDrawerOpen) onIntent(AiStudioScreenIntent.SetDrawerOpen(true))
    if (!state.sidebar.isSearchVisible) {
        onIntent(AiStudioScreenIntent.ToggleSearch)
    } else if (!isAttached) {
        if (!isCompact) onIntent(AiStudioScreenIntent.ToggleSidebar)
    }
    focus.searchRequest++
}
