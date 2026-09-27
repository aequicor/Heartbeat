package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val toolExpansionLog = Log.tag("DS/ToolExpansion")

/** Transcript-owned disclosure, retained independently of the currently composed lazy rows. */
@Stable
public class HbToolExpansionState {
    internal var expandedKeys: PersistentSet<String> by mutableStateOf(persistentSetOf())
        private set

    /** Checks a tool within its message; equal tool ids in different messages remain independent. */
    public fun isExpanded(messageId: String, toolId: String): Boolean = toolKey(messageId, toolId) in expandedKeys

    /** Changes disclosure without scrolling the transcript or replacing its message data. */
    public fun setExpanded(messageId: String, toolId: String, isExpanded: Boolean) {
        val key = toolKey(messageId, toolId)
        if ((key in expandedKeys) == isExpanded) return
        expandedKeys = if (isExpanded) expandedKeys.adding(key) else expandedKeys.removing(key)
        toolExpansionLog.i { "tool disclosure updated message=$messageId tool=$toolId expanded=$isExpanded" }
    }

    internal companion object {
        val Saver = listSaver<HbToolExpansionState, String>(
            save = { it.expandedKeys.toList() },
            restore = { keys -> HbToolExpansionState().apply { expandedKeys = keys.toPersistentSet() } },
        )
    }
}

/** Saves expanded tool identities across host recreation, not just lazy row disposal. */
@Composable
public fun rememberHbToolExpansionState(): HbToolExpansionState =
    rememberSaveable(saver = HbToolExpansionState.Saver) { HbToolExpansionState() }

private fun toolKey(messageId: String, toolId: String): String = "message:${messageId.length}:$messageId:tool:$toolId"
