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

    /** Follows the focused chat of every screen state; equal chats do not restart the lookup. */
    @HighFrequency
    fun focus(state: AiStudioScreenState) {
        val chat = state.focusedChat()
        if (chat == chats.value) return
        log.v { "focused chat changed" }
        chats.value = chat
    }

    suspend fun observe(pipeline: StudioPipeline) = with(pipeline) {
        combine(chats, configuration.state) { chat, config -> chat to config }.collectLatest { (chat, config) ->
            val notice = chat?.let { notice(it, config) }
            updateState { copy(projectTrust = notice) }
        }
    }

    suspend fun decide(intent: AiStudioScreenIntent.DecideProjectTrust) {
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

/** The project and engine of the focused chat. */
private data class FocusedChat(val project: WorkspaceRef, val engine: EngineId, val title: String)

private fun AiStudioScreenState.focusedChat(): FocusedChat? {
    val pane = panes.firstOrNull { it.id == focusedPaneId } ?: return null
    val session = pane.sessionId?.let(::session)
    val projectId = session?.projectId ?: pane.projectId ?: return null
    val modelId = session?.let { configurations[it.id]?.modelId ?: it.modelId } ?: settings.modelId
    val engine = studioModelTarget(modelId)?.engine ?: return null
    return FocusedChat(WorkspaceRef(projectId), engine, project(projectId)?.name ?: projectId)
}
