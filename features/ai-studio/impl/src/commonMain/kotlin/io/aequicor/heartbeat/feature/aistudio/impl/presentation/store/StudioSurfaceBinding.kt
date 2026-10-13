package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePrompt
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraftCaret
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
    @ForScope(AiStudioScope::class) scope: ScopeHandle,
) {
    private val log = Log.tag("StudioSurfaceBinding")
    private val panes = MutableStateFlow<Map<Int, PaneSurfaceUi>>(emptyMap())
    private val cards = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())

    private var seen: SeenInputs? = null
    private val latestWorkspace = MutableStateFlow<StudioWorkspace?>(null)

    /** The open panes, for the slot hosts of the component. */
    internal val openPanes: StateFlow<Map<Int, PaneSurfaceUi>> = panes.asStateFlow()

    /** Cards shown in the reply feed of each pane, for the card hosts of the component. */
    internal val cardIds: StateFlow<Map<Int, Set<String>>> = cards.asStateFlow()

    init {
        // Places live as long as the studio window, not its subscription: a panel stays open across settings.
        scope.coroutineScope.launch {
            val places = combine(panes, backend.repository().observeWorkspace()) { panes, workspace ->
                latestWorkspace.value = workspace
                log.v { "Interface workspace projection updated" }
                panes.mapValues { (_, pane) -> pane.place(workspace) }
            }.distinctUntilChanged()
            surfaces.keep(places)
        }
    }

    /** Follows the screen [state]; called for every state of the store, so unchanged inputs return at once. */
    @HighFrequency
    internal fun onState(state: AiStudioScreenState) {
        if (seen?.isSameAs(state) == true) return
        seen = SeenInputs(state.panes, state.harnessChoices, state.transcripts)
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
        val shown = state.panes.associate { pane ->
            val messages = pane.sessionId?.let { state.transcripts[it] }.orEmpty()
            pane.id to messages.flatMapTo(mutableSetOf()) { (it as? MessageUi.Reply)?.cardIds.orEmpty() }
        }.filterValues { it.isNotEmpty() }
        if (cards.value != shown) {
            log.v { "Interface cards: ${cards.value.size} -> ${shown.size}" }
            cards.value = shown
        }
    }

    /**
     * Handles prompts of surfaces while the studio is shown; [submit] sends the draft of a pane. A prompt joins a
     * draft the user started instead of replacing it, and is sent only into an empty composer and never as a host
     * command. The submit callback checks the supplied guard under its submission state lock, so a surface cannot
     * send the user's text, attachments or a `/compact` or `/remember` directive.
     */
    internal suspend fun observe(
        pipeline: SurfacePipeline,
        submit: suspend (paneId: Int, isCurrent: (AiStudioScreenState) -> Boolean) -> Unit,
    ) {
        surfaces.prompts.collect { prompt -> applyPrompt(pipeline, prompt, submit) }
    }

    private suspend fun applyPrompt(
        pipeline: SurfacePipeline,
        prompt: StudioSurfacePrompt,
        submit: suspend (paneId: Int, isCurrent: (AiStudioScreenState) -> Boolean) -> Unit,
    ) {
        val paneId = prompt.paneId
        if (paneId !in panes.value) return
        var isSent = false
        var isApplied = false
        var expectedKey: String? = null
        pipeline.updateState {
            isSent = false
            isApplied = false
            expectedKey = null
            if (!accepts(prompt)) return@updateState this
            isApplied = true
            val key = draftKey(paneId)
            expectedKey = key
            val draft = drafts[key].orEmpty()
            val isEmpty = draft.isBlank() && draftAttachments[key].isNullOrEmpty()
            val text = if (draft.isBlank()) prompt.text else draft.trimEnd() + "\n\n" + prompt.text
            isSent = prompt.isSubmitted && isEmpty && !prompt.text.trimStart().startsWith("/")
            withDraft(paneId, text).withDraftCaret(paneId, text.length)
        }
        if (!isApplied) return
        log.i { "Interface prompt for pane $paneId submitted=$isSent" }
        if (isSent) {
            submit(paneId) { state ->
                state.accepts(prompt) && state.draftKey(paneId) == expectedKey &&
                    state.draft(paneId) == prompt.text && state.attachments(paneId).isEmpty() &&
                    !state.isAttachmentPending(paneId)
            }
        }
    }

    /** Revalidates a delayed output against the screen state whose draft will actually change. */
    private fun AiStudioScreenState.accepts(prompt: StudioSurfacePrompt): Boolean {
        val pane = panes.firstOrNull { it.id == prompt.paneId } ?: return false
        val workspace = latestWorkspace.value ?: return false
        val selected = if (pane.sessionId == null) harnessChoices.panes[pane.id].orEmpty() else emptySet()
        return PaneSurfaceUi(pane.sessionId, pane.projectId, selected).place(workspace) == prompt.place
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

/** Inputs of [StudioSurfaceBinding.onState] by identity: an unchanged state reuses its collections. */
private class SeenInputs(val panes: Any, val choices: Any, val transcripts: Any) {
    fun isSameAs(state: AiStudioScreenState): Boolean =
        panes === state.panes && choices === state.harnessChoices && transcripts === state.transcripts
}
