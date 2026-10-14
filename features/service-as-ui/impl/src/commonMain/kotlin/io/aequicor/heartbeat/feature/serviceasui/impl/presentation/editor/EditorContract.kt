package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.PlacementUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Whether the surface can be edited. */
internal enum class EditorPhase { Loading, Disabled, Failed, Missing, Ready }

/** A finding of the spec check at the JSON [path]; [message] is the format's own text, as the agent sees it. */
@Immutable
internal data class DiagnosticUi(val path: String, val message: String, val isWarning: Boolean)

/** What the surface is and where it shows; edited only by the agent, shown read-only. */
@Immutable
internal data class SurfaceInfoUi(
    val title: String = "",
    val description: String = "",
    val owner: String = "",
    val placement: PlacementUi = PlacementUi.Panel,
    val launchers: ImmutableList<PlacementUi> = persistentListOf(),
    val revision: Long = 0,
    val isEnabled: Boolean = false,
)

/**
 * The editor: the saved surface [info] and the draft [text] of its spec with [diagnostics], which describe [text]
 * only when [isChecked]: checks run a moment after the user stops typing. [isDirty] drafts survive
 * changes of the saved surface; [isChangedElsewhere] tells that it was changed meanwhile, so saving the draft would
 * conflict. [previewKey] finds the store of the preview of the last valid draft. [base] and [baseSpec] are the
 * revision and stored spec the draft was made from.
 */
@Immutable
internal data class EditorState(
    val phase: EditorPhase = EditorPhase.Loading,
    val info: SurfaceInfoUi = SurfaceInfoUi(),
    val text: String = "",
    val diagnostics: ImmutableList<DiagnosticUi> = persistentListOf(),
    val isValid: Boolean = false,
    val isDirty: Boolean = false,
    val isChecked: Boolean = false,
    val isChangedElsewhere: Boolean = false,
    val isSaving: Boolean = false,
    val notice: WriteNoticeUi? = null,
    val previewKey: String? = null,
    val base: Long = 0,
    val baseSpec: String = "",
) : MVIState {
    override fun toString(): String =
        "EditorState(phase=$phase, revision=${info.revision}, isDirty=$isDirty, isValid=$isValid, " +
            "diagnostics=${diagnostics.size}, isSaving=$isSaving)"
}

/** Controls of the editor. */
internal sealed interface EditorIntent : MVIIntent {
    /** The draft spec changed. */
    data class ChangeText(val text: String) : EditorIntent {
        override fun toString(): String = "ChangeText(***)"
    }

    /** Saves a valid draft over the revision it was made from. */
    data object Save : EditorIntent

    /** Drops the draft and shows the saved spec. */
    data object Revert : EditorIntent

    /** Keeps the draft over a surface changed meanwhile; saving it replaces that change. */
    data object KeepDraft : EditorIntent

    /** Shows or hides the surface everywhere. */
    data class SetEnabled(val isEnabled: Boolean) : EditorIntent

    /** Deletes the surface; the screen asks the user first. */
    data object Delete : EditorIntent

    /** Reads the library again after it failed to load. */
    data object Reload : EditorIntent

    /** Hides the notice. */
    data object DismissNotice : EditorIntent
}

/** Navigation the component performs. */
internal sealed interface EditorAction : MVIAction {
    /** The surface was deleted; the editor closes. */
    data object Deleted : EditorAction
}
