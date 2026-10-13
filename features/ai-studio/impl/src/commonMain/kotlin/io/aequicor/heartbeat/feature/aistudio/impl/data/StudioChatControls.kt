package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineKey
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatComposer
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatModel
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineKey
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The composer and sidebar controls of [ProfileStudioChats]: models with the settings of a new chat, a chat's
 * settings and its metadata. Changes take the studio's own paths: chat settings go to the runtime's configuration
 * (the screen reflects them), new-chat effort to the profile's effort choices, metadata through the studio machine
 * while the screen runs (so it also closes an archived chat's pane), else straight to the repository.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioChatControls(
    private val repository: Lazy<StudioRepository>,
    private val runtime: Lazy<StudioRuntime>,
    private val efforts: EffortChoicesView,
    private val machines: MachineRegistry,
) {
    private val log = Log.tag("StudioChatControls")

    /** Models and the start page's settings; re-read whenever the models or effort choices change. */
    fun observeComposer(): Flow<StudioChatComposer> =
        combine(repository.value.observeModels(), efforts.state) { models, choices ->
            StudioChatComposer(
                models = models.map { it.toChatModel(choices) },
                newChat = runtime.value.defaults().toNewChat(models),
            )
        }

    /**
     * Run settings of a new chat: the caller's model and approvals when that model is offered (and works in a
     * project when the chat has one), else the start page's; `null` without a usable model.
     */
    suspend fun newChatSettings(requested: StudioChatSettings?, projectId: String?): RunSettings? {
        val defaults = runtime.value.defaults()
        val models = repository.value.observeModels().first()
        val modelId = requested?.modelId ?: defaults.modelId
        val model = models.firstOrNull { it.id == modelId } ?: return null
        if (projectId != null && !model.isLocalProjectSupported) return null
        return defaults.copy(modelId = model.id, approval = requested?.approval ?: defaults.approval)
    }

    /** Applies [change] to the existing chat [chatId] and waits for the engine's answer. */
    suspend fun configure(chatId: String, change: StudioSettingChange): Boolean {
        val chat = editableChat(chatId) ?: return false
        val pending = runtime.value.state.value.configurations[chatId]?.pendingOperation
        if (chat.isArchived || pending != null || !isOffered(change)) {
            log.i { "Outside chat setting refused kind=${change::class.simpleName.orEmpty()}" }
            return false
        }
        log.i { "Outside chat setting kind=${change::class.simpleName.orEmpty()}" }
        return try {
            runtime.value.configure(chatId, change)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redactedFailure()) { "Outside chat setting failed" }
            false
        }
    }

    /** Sends the effort of new chats on [modelId] to the profile's effort choices, as the start page does. */
    suspend fun chooseNewChatEffort(modelId: String, effort: String?): Boolean {
        val model = repository.value.observeModels().first().firstOrNull { it.id == modelId }
        val target = studioModelTarget(modelId)
        val isOffered = effort == null || model?.reasoningEfforts.orEmpty().contains(effort)
        if (model == null || target == null || !isOffered) return false
        val result = machines.send(
            EffortConfigurationMachineKey,
            EffortConfigurationIntent.Public.Select(target, effort),
        )
        log.i { "Outside new-chat effort choice result=$result" }
        return result == SendResult.Accepted
    }

    /** Renames, pins, marks or archives a listed chat. */
    suspend fun edit(chatId: String, edit: SessionEdit): Boolean {
        val normalized = if (edit is SessionEdit.Rename) edit.copy(title = edit.title.trim()) else edit
        if (editableChat(chatId) == null || (normalized is SessionEdit.Rename && normalized.title.isEmpty())) {
            return false
        }
        val viaScreen = machines.send(AiStudioMachineKey, AiStudioIntent.Public.Edit(chatId, normalized))
        log.i { "Outside chat edit kind=${normalized::class.simpleName.orEmpty()} viaScreen=$viaScreen" }
        if (viaScreen != SendResult.Accepted) repository.value.edit(chatId, normalized)
        return true
    }

    private suspend fun editableChat(chatId: String): StudioSession? =
        repository.value.observeWorkspace().first().session(chatId)?.takeIf { it.parentChatId == null }

    private suspend fun isOffered(change: StudioSettingChange): Boolean = change !is StudioSettingChange.Model ||
        repository.value.observeModels().first().any { it.id == change.modelId }
}

private fun StudioModel.toChatModel(choices: EffortConfigurationState): StudioChatModel {
    val target = studioModelTarget(id)
    val chosen = if (target != null && choices is EffortConfigurationState.Ready) choices.effortFor(target) else null
    return StudioChatModel(
        id = id,
        name = name,
        shortName = shortName,
        efforts = reasoningEfforts,
        newChatEffort = chosen?.takeIf { it in reasoningEfforts },
        isTrustSupported = isTrustSupported,
        isProjectSupported = isLocalProjectSupported,
        connection = target?.takeIf { isModelSwitchSupported }?.let { "${it.engine.value}/${it.binding.value}" },
    )
}

private fun RunSettings.toNewChat(models: List<StudioModel>): StudioChatSettings? =
    modelId.takeIf { id -> models.any { it.id == id } }?.let { StudioChatSettings(it, approval = approval) }
