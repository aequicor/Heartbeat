package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectTrustNoticeUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/**
 * Asks to decide whether the project of the focused chat may load what it configures for the chat's engine:
 * shown while the decision is open and the project has such configuration. The decision is the engine
 * configuration's; until it is made, only the project's instructions load.
 */
internal class StudioProjectTrust(private val configuration: EngineConfiguration, private val facade: EngineFacade) {
    private val log = Log.tag("StudioProjectTrust")
    private val chats = MutableStateFlow<FocusedChat?>(null)
    private var seen: FocusInputs? = null

    /**
     * Follows the focused chat of every screen state. Most updates (streamed tokens, carets) leave the panes, chats and
     * settings as they were, so the chat is derived again only when one of them is replaced.
     */
    @HighFrequency
    fun focus(state: AiStudioScreenState) {
        val inputs = FocusInputs(state)
        if (seen?.isSameAs(inputs) == true) return
        seen = inputs
        val chat = state.focusedChat()
        if (chat == chats.value) return
        log.v { "focused chat changed" }
        chats.value = chat
    }

    suspend fun observe(pipeline: StudioPipeline) = with(pipeline) {
        combine(chats, configuration.state) { chat, config -> chat to config }.collectLatest { (chat, config) ->
            val notice = if (chat == null) null else notice(chat, config)
            updateState { copy(projectTrust = notice) }
        }
    }

    /** Saves the decision the user made on [notice]; a decision about anything else is refused. */
    suspend fun decide(intent: AiStudioScreenIntent.DecideProjectTrust, notice: ProjectTrustNoticeUi?) {
        if (notice == null || notice.projectId != intent.projectId || notice.engine != intent.engine) {
            log.w { "ignore a project trust decision that answers no open question" }
            return
        }
        val trust = if (intent.isTrusted) ProjectTrust.Trusted else ProjectTrust.Content
        log.i { "project trust decided engine=${intent.engine} trust=$trust" }
        try {
            configuration.setProjectTrust(EngineId(intent.engine), WorkspaceRef(intent.projectId), trust)
        } catch (e: ConfigException) {
            log.w(e) { "project trust not saved engine=${intent.engine}" }
        }
    }

    private suspend fun notice(chat: FocusedChat, config: EngineConfigurationState): ProjectTrustNoticeUi? {
        val engine = config.takeIf { it.isEnabled }?.engine(chat.engine) ?: return null
        val decided = engine.trust[chat.project]?.takeIf { it != ProjectTrust.Undecided }
        if (ConfigCapability.ProjectTrust !in engine.schema.capabilities || decided != null) return null
        val status = try {
            configuration.projectTrust(chat.engine, chat.project)
        } catch (e: ConfigException) {
            log.w(e) { "project trust unknown engine=${chat.engine.value}" }
            return null
        }
        if (status.trust != ProjectTrust.Undecided || !status.hasGatedConfiguration) return null
        val title = facade.engines.state.value.firstOrNull { it.descriptor.id == chat.engine }?.descriptor?.title
        return ProjectTrustNoticeUi(chat.project.value, chat.engine.value, chat.title, title ?: chat.engine.value)
    }
}

/** What the focused chat is derived from, compared by reference. */
private class FocusInputs(state: AiStudioScreenState) {
    private val panes = state.panes
    private val focused = state.focusedPaneId
    private val sessions = state.sessions
    private val projects = state.projects
    private val configurations = state.configurations
    private val settings = state.settings

    fun isSameAs(other: FocusInputs): Boolean = panes === other.panes && focused == other.focused &&
        sessions === other.sessions && projects === other.projects &&
        configurations === other.configurations && settings === other.settings
}

/** The project and engine of the focused chat. */
private data class FocusedChat(val project: WorkspaceRef, val engine: EngineId, val title: String)

private fun AiStudioScreenState.focusedChat(): FocusedChat? {
    val pane = panes.firstOrNull { it.id == focusedPaneId } ?: return null
    val session = pane.sessionId?.let(::session)
    // A project no longer in the saved list has nothing to decide.
    val projectId = (session?.projectId ?: pane.projectId)?.takeIf { project(it) != null } ?: return null
    val modelId = session?.let { configurations[it.id]?.modelId ?: it.modelId } ?: settings.modelId
    val engine = studioModelTarget(modelId)?.engine ?: return null
    return FocusedChat(WorkspaceRef(projectId), engine, project(projectId)?.name.orEmpty())
}
