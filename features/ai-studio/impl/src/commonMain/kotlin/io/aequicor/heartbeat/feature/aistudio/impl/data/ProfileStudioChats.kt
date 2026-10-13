package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatComposer
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatList
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatRejection
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSend
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioChats
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.titleOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select

/** Run settings of a chat's next turn started outside the screen. */
internal fun interface StudioChatRunSettings {
    /** The chat's confirmed model and approval over the studio defaults. */
    suspend fun settings(chatId: String): RunSettings
}

/**
 * [StudioChatRunSettings] from the studio's chat records, the way scheduled turns are configured; a chat without
 * a model of its own takes the start page's one.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredStudioChatRunSettings(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val runtime: StudioRuntime,
) : StudioChatRunSettings {
    override suspend fun settings(chatId: String): RunSettings {
        val record = checkNotNull(stores.keyValue(ChatSpec).get(ChatsKey).orEmpty().firstOrNull { it.id == chatId }) {
            "Unknown studio conversation"
        }
        val defaults = runtime.defaults()
        val settings = hostRunSettings(record, runtime.state.value.configurations[chatId]?.applied, defaults)
        // A chat created outside the screen has no route yet: it takes the start page's model, as a new chat there.
        return if (settings.modelId.isBlank()) settings.copy(modelId = defaults.modelId) else settings
    }
}

/**
 * Profile chats for hosts outside the screen. Turns run in the profile scope like the screen's: the shared run
 * coordinator keeps one turn per chat, so the screen shows them running and can stop them. Sending returns once the
 * engine accepted the turn; a caller giving up before that cancels it, never after.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileStudioChats(
    private val toggles: FeatureToggles,
    private val repository: Lazy<StudioRepository>,
    private val runtime: Lazy<StudioRuntime>,
    private val settings: Lazy<StudioChatRunSettings>,
    private val controls: Lazy<StudioChatControls>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : StudioChats {
    private val log = Log.tag("ProfileStudioChats")

    override val availability: Flow<Boolean> = toggles.observe(StudioEngineRuntime).distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeList(): Flow<StudioChatList> = availability.flatMapLatest { isAvailable ->
        if (isAvailable) {
            combine(repository.value.observeWorkspace(), runtime.value.state) { workspace, state ->
                workspace.toChatList(state)
            }
        } else {
            flowOf(StudioChatList())
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeComposer(): Flow<StudioChatComposer> = availability.flatMapLatest { isAvailable ->
        if (isAvailable) controls.value.observeComposer() else flowOf(StudioChatComposer())
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTranscript(chatId: String): Flow<List<StudioChatEntry>> =
        availability.flatMapLatest { isAvailable ->
            if (isAvailable) {
                repository.value.observeMessages(chatId).map { it.toChatEntries() }
            } else {
                flowOf(emptyList())
            }
        }.distinctUntilChanged()

    override suspend fun send(request: StudioChatSend): StudioChatSendResult {
        val prompt = request.prompt.trim()
        require(prompt.isNotEmpty()) { "Empty prompt" }
        val result = when (val target = target(request, prompt)) {
            is ChatTarget.Refused -> StudioChatSendResult.Rejected(target.reason)

            is ChatTarget.Chat -> if (start(target, prompt, request.attachments)) {
                StudioChatSendResult.Accepted(target.id)
            } else {
                StudioChatSendResult.Rejected(StudioChatRejection.Failed)
            }
        }
        log.i {
            "Outside prompt in a studio chat isNew=${request.chatId == null} " +
                "result=${(result as? StudioChatSendResult.Rejected)?.reason ?: "accepted"}"
        }
        return result
    }

    override suspend fun stop(chatId: String): Boolean {
        if (chatId !in runtime.value.state.value.running) return false
        log.i { "Outside stop of a studio chat" }
        runtime.value.cancel(chatId)
        return true
    }

    override suspend fun respond(
        chatId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ): Boolean {
        val permission = runtime.value.state.value.permissions
            .firstOrNull { it.sessionId == chatId && it.requestId == requestId }
        if (permission == null || permission.options.none { it.id == optionId }) {
            log.i { "Outside permission answer is stale" }
            return false
        }
        log.i { "Outside permission answer option=$optionId" }
        runtime.value.respond(chatId, requestId, optionId, answer)
        return true
    }

    override suspend fun configure(chatId: String, change: StudioSettingChange): Boolean =
        toggles.get(StudioEngineRuntime) && controls.value.configure(chatId, change)

    override suspend fun chooseNewChatEffort(modelId: String, effort: String?): Boolean =
        toggles.get(StudioEngineRuntime) && controls.value.chooseNewChatEffort(modelId, effort)

    override suspend fun edit(chatId: String, edit: SessionEdit): Boolean =
        toggles.get(StudioEngineRuntime) && controls.value.edit(chatId, edit)

    private suspend fun target(request: StudioChatSend, prompt: String): ChatTarget {
        val existing = request.chatId
        return when {
            !toggles.get(StudioEngineRuntime) -> ChatTarget.Refused(StudioChatRejection.Unavailable)
            existing != null -> checkExisting(existing)?.let(ChatTarget::Refused) ?: ChatTarget.Chat(existing, null)
            else -> newChat(request, prompt)
        }
    }

    private suspend fun checkExisting(chatId: String): StudioChatRejection? {
        val chat = repository.value.observeWorkspace().first().session(chatId)
        return when {
            chat == null || chat.isArchived || chat.parentChatId != null -> StudioChatRejection.UnknownChat
            !chat.isContinuable -> StudioChatRejection.NotContinuable
            chatId in runtime.value.state.value.running -> StudioChatRejection.Busy
            else -> null
        }
    }

    private suspend fun newChat(request: StudioChatSend, prompt: String): ChatTarget {
        val projectId = request.projectId
        if (projectId != null && repository.value.observeWorkspace().first().project(projectId) == null) {
            return ChatTarget.Refused(StudioChatRejection.UnknownProject)
        }
        val runSettings = controls.value.newChatSettings(request.settings, projectId)
            ?: return ChatTarget.Refused(StudioChatRejection.NoModel)
        log.i { "Create a studio chat for an outside prompt hasProject=${projectId != null}" }
        return ChatTarget.Chat(repository.value.createSession(projectId, titleOf(prompt)).id, runSettings)
    }

    /** Runs the turn in the profile and waits only for the engine's acceptance. */
    private suspend fun start(target: ChatTarget.Chat, prompt: String, attachments: List<ResourceRef>): Boolean {
        val chatId = target.id
        val runSettings = target.settings ?: settings.value.settings(chatId)
        val accepted = CompletableDeferred<Unit>()
        val run = profile.coroutineScope.async {
            try {
                runtime.value.run(chatId, prompt, runSettings, attachments) { accepted.complete(Unit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e.redactedFailure()) { "Outside studio turn failed before acceptance" }
                RunOutcome.Failed
            }
        }
        var isSettled = false
        try {
            select {
                accepted.onAwait { Unit }
                run.onAwait { Unit }
            }
            isSettled = true
        } finally {
            if (!isSettled && !accepted.isCompleted) run.cancel()
        }
        return accepted.isCompleted
    }

    /** Where an outside prompt goes. */
    private sealed interface ChatTarget {
        /** A chat to run in; [settings] of a new chat, `null` for an existing one's own. */
        data class Chat(val id: String, val settings: RunSettings?) : ChatTarget

        data class Refused(val reason: StudioChatRejection) : ChatTarget
    }
}
