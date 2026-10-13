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

/** A surface in pane [paneId] asks to put [text] into its composer, or to send it when [isSubmitted]. */
data class StudioSurfacePrompt(val paneId: Int, val text: String, val isSubmitted: Boolean) {
    override fun toString(): String = "StudioSurfacePrompt(paneId=$paneId, isSubmitted=$isSubmitted)"
}

/** Interfaces the agent built, as the panes of one studio window use them. */
interface StudioSurfaces {
    /** Whether interfaces are turned on. */
    val availability: Flow<Boolean>

    /** Prompts surfaces in the panes of this window ask for. */
    val prompts: Flow<StudioSurfacePrompt>

    /** The place id of pane [paneId] for the slot routes of interfaces. */
    fun host(paneId: Int): String

    /** Keeps the places of the panes in [places] bound until cancelled; panes leaving the map are unbound. */
    suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>)

    /** A studio without interfaces. */
    object None : StudioSurfaces {
        override val availability: Flow<Boolean> = flowOf(false)
        override val prompts: Flow<StudioSurfacePrompt> = emptyFlow()

        override fun host(paneId: Int): String = "none.pane-$paneId"

        override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) = awaitCancellation()
    }
}
