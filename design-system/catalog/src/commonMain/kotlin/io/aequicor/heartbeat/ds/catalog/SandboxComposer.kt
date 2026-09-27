package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbChatComposer
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf

private val composerLog = Log.tag("DS/SandboxComposer")

@Composable
internal fun SandboxComposer(
    state: DemoChatState,
    copy: ChatDemoCopy,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    val menus = remember { ComposerMenus() }
    HbChatComposer(
        value = state.draft,
        onValueChange = state::updateDraft,
        onSend = { state.send(copy) },
        onStop = state::stop,
        sendLabel = hbString(HbString.Send),
        stopLabel = hbString(HbString.Stop),
        modifier = modifier.fillMaxWidth(),
        inputMaxHeight = if (isCompact) HbTheme.dimensions.composerMinHeight else HbTheme.dimensions.composerMaxHeight,
        placeholder = hbString(HbString.ComposerPlaceholder),
        isStreaming = state.isStreaming,
        enabled = !state.isLoadingHistory,
        leadingContent = { AddContextMenu(state, copy, menus, isCompact) },
        trailingContent = { ResponseMenus(state, menus, isCompact) },
    )
}

@Composable
private fun AddContextMenu(state: DemoChatState, copy: ChatDemoCopy, menus: ComposerMenus, isCompact: Boolean) {
    val noteDraft = hbString(HbString.NoteDraft)
    val actions = persistentListOf(
        HbComposerAction("note", hbString(HbString.InsertNote), hbString(HbString.InsertNoteHint)),
        HbComposerAction("markdown", hbString(HbString.MarkdownDemo), hbString(HbString.MarkdownDemoHint)),
        HbComposerAction("tool", hbString(HbString.ToolDemo), hbString(HbString.ToolDemoHint)),
        HbComposerAction("session", hbString(HbString.LongSession), hbString(HbString.LongSessionHint)),
    ).let { commands ->
        if (isCompact) commands.add(HbComposerAction("earlier", hbString(HbString.LoadEarlier))) else commands
    }

    HbComposerMenuButton(
        label = hbString(HbString.AddContext),
        actions = actions,
        isExpanded = menus.expanded == "add",
        onExpandedChange = { menus.setExpanded("add", it) },
        onAction = { action ->
            when (action) {
                "note" -> state.updateDraft(noteDraft)
                "markdown" -> state.addExample(copy, isTool = false)
                "tool" -> state.addExample(copy, isTool = true)
                "session" -> state.loadLongSession(copy)
                "earlier" -> state.loadEarlier(copy)
            }
        },
        enabled = !state.isStreaming && !state.isLoadingHistory,
        headerLabel = hbString(HbString.AddContext),
        accessibleLabel = hbString(HbString.AddContext),
        icon = HbIcons.Plus,
    )
}

@Composable
private fun RowScope.ResponseMenus(state: DemoChatState, menus: ComposerMenus, isCompact: Boolean) {
    val modeLabel = hbString(if (state.isPlanning) HbString.PlanMode else HbString.AskMode)
    val modelLabel = hbString(if (state.isConcise) HbString.ConciseModel else HbString.DemoModel)
    HbComposerMenuButton(
        label = modeLabel,
        actions = persistentListOf(
            HbComposerAction("ask", hbString(HbString.AskMode)),
            HbComposerAction("plan", hbString(HbString.PlanMode)),
        ),
        isExpanded = menus.expanded == "mode",
        onExpandedChange = { menus.setExpanded("mode", it) },
        onAction = { state.selectPlanning(it == "plan") },
        modifier = Modifier.semantics { stateDescription = modeLabel },
        headerLabel = if (isCompact) hbString(HbString.ModeMenu) else null,
        accessibleLabel = hbString(HbString.ModeMenu),
        icon = if (isCompact) HbIcons.Plan else null,
    )
    HbComposerMenuButton(
        label = modelLabel,
        actions = persistentListOf(
            HbComposerAction("full", hbString(HbString.DemoModel)),
            HbComposerAction("brief", hbString(HbString.ConciseModel)),
        ),
        isExpanded = menus.expanded == "model",
        onExpandedChange = { menus.setExpanded("model", it) },
        onAction = { state.selectConcise(it == "brief") },
        modifier = Modifier.semantics { stateDescription = modelLabel },
        headerLabel = if (isCompact) hbString(HbString.ModelMenu) else null,
        accessibleLabel = hbString(HbString.ModelMenu),
        icon = if (isCompact) HbIcons.Sparkles else null,
    )
}

@Stable
private class ComposerMenus {
    var expanded by mutableStateOf<String?>(null)
        private set

    fun setExpanded(menu: String, isOpen: Boolean) {
        composerLog.i { "menu=$menu expanded=$isOpen" }
        expanded = if (isOpen) menu else null
    }
}
