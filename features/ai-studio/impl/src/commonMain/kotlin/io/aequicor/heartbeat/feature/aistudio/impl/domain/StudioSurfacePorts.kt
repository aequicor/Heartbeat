package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * What a pane offers interfaces the agent built: its native [chat] (null for a new chat), the [workspace] it works in
 * and, for a new chat, the [harnesses] selected for it.
 */
data class StudioSurfacePlace(
    val chat: SessionRef? = null,
    val workspace: WorkspaceRef? = null,
    val harnesses: Set<String> = emptySet(),
)

/** A surface in [place] of pane [paneId] asks to insert [text], or send it when [isSubmitted]. */
data class StudioSurfacePrompt(
    val paneId: Int,
    val text: String,
    val isSubmitted: Boolean,
    val place: StudioSurfacePlace = StudioSurfacePlace(),
) {
    override fun toString(): String = "StudioSurfacePrompt(paneId=$paneId, isSubmitted=$isSubmitted)"
}

/** Reveals a stored interface card only in the pane still showing its original [place]. */
data class StudioSurfaceCardFocus(val paneId: Int, val cardId: String, val place: StudioSurfacePlace)

/** The canonical app selected for one exact [place]; metadata never grants method-call authority. */
data class StudioSurfaceApp(
    val surfaceId: String,
    val title: String,
    val harnessId: String?,
    val place: StudioSurfacePlace,
)

/** Interfaces the agent built, as the panes of one studio window use them. */
interface StudioSurfaces {
    /** Whether interfaces are turned on. */
    val availability: Flow<Boolean>

    /** Prompts surfaces in the panes of this window ask for. */
    val prompts: Flow<StudioSurfacePrompt>

    /** Requests to reveal an existing card in its chat. */
    val cardFocus: Flow<StudioSurfaceCardFocus>

    /** Selected apps of this window, keyed by pane; stale or unbound contexts are absent. */
    val apps: Flow<Map<Int, StudioSurfaceApp>> get() = flowOf(emptyMap())

    /** Opens [app] only while the pane still owns its exact place and canonical surface. */
    suspend fun openApp(paneId: Int, app: StudioSurfaceApp) = Unit

    /** The place id of pane [paneId] for the slot routes of interfaces. */
    fun host(paneId: Int): String

    /** The dock of this window for the panel route of interfaces. */
    val dock: String

    /** The opening sequence of the panel open in [dock], or null while none is open. */
    val panel: Flow<Long?>

    /** Closes the panel open in [dock]. */
    suspend fun closePanel()

    /** Keeps the places of the panes in [places] bound until cancelled; panes leaving the map are unbound. */
    suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>)

    /** A studio without interfaces. */
    object None : StudioSurfaces {
        override val availability: Flow<Boolean> = flowOf(false)
        override val prompts: Flow<StudioSurfacePrompt> = emptyFlow()
        override val cardFocus: Flow<StudioSurfaceCardFocus> = emptyFlow()

        override fun host(paneId: Int): String = "none.pane-$paneId"

        override val dock: String = "none.side"
        override val panel: Flow<Long?> = flowOf(null)

        override suspend fun closePanel() = Unit

        override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) = awaitCancellation()
    }
}
