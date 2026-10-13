package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext

private typealias SurfacePipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/** What a pane is for interfaces, as the screen state knows it. */
internal data class PaneSurfaceUi(val chatId: String?, val projectId: String?, val harnesses: Set<String>)

/**
 * Panes as places of interfaces the agent built: binds each pane's chat, workspace and new-chat harnesses while the
 * studio is shown, and puts prompts the surfaces ask for into the composer of their pane, sending them when asked.
 */
@SingleIn(AiStudioScope::class)
@Inject
class StudioSurfaceBinding internal constructor(
    private val surfaces: StudioSurfaces,
    private val backend: StudioBackend,
) {
    private val log = Log.tag("AiStudioModel")
    private val panes = MutableStateFlow<Map<Int, PaneSurfaceUi>>(emptyMap())
    private val cards = MutableStateFlow<Set<String>>(emptySet())

    /** The open panes, for the slot hosts of the component. */
    internal val paneIds: StateFlow<Map<Int, PaneSurfaceUi>> = panes.asStateFlow()

    /** Cards shown in the replies of the open transcripts, for the card hosts of the component. */
    internal val cardIds: StateFlow<Set<String>> = cards.asStateFlow()

    /** Follows the screen [state]; called for every state of the store. */
    internal fun onState(state: AiStudioScreenState) {
        val next = state.panes.associate { pane ->
            pane.id to PaneSurfaceUi(
                chatId = pane.sessionId,
                projectId = pane.projectId,
                harnesses = if (pane.sessionId == null) state.harnessChoices.panes[pane.id].orEmpty() else emptySet(),
            )
        }
        if (panes.value != next) {
            log.v { "Interface panes: ${panes.value.size} -> ${next.size}" }
            panes.value = next
        }
        val shown = state.transcripts.values.flatMapTo(mutableSetOf()) { messages ->
            messages.flatMap { (it as? MessageUi.Reply)?.cardIds.orEmpty() }
        }
        if (cards.value != shown) {
            log.v { "Interface cards: ${cards.value.size} -> ${shown.size}" }
            cards.value = shown
        }
    }

    /** Binds the panes and handles prompts until cancelled; [submit] sends the draft of a pane. */
    internal suspend fun observe(pipeline: SurfacePipeline, submit: suspend (paneId: Int) -> Unit) = coroutineScope {
        launch {
            val places = combine(panes, backend.repository().observeWorkspace()) { panes, workspace ->
                panes.mapValues { (_, pane) -> pane.place(workspace) }
            }.distinctUntilChanged()
            surfaces.keep(places)
        }
        launch {
            surfaces.prompts.collect { prompt ->
                if (prompt.paneId !in panes.value) return@collect
                log.i { "Interface prompt for pane ${prompt.paneId} submitted=${prompt.isSubmitted}" }
                pipeline.updateState {
                    withDraft(prompt.paneId, prompt.text).withDraftCaret(prompt.paneId, prompt.text.length)
                }
                if (prompt.isSubmitted) submit(prompt.paneId)
            }
        }
    }
}

private fun PaneSurfaceUi.place(workspace: StudioWorkspace): StudioSurfacePlace {
    val session = workspace.session(chatId)
    val project = (session?.projectId ?: projectId)?.let(::WorkspaceRef)
    return StudioSurfacePlace(
        chat = session?.nativeSession,
        workspace = session?.treeAccess?.workspace ?: project,
        harnesses = harnesses,
    )
}
