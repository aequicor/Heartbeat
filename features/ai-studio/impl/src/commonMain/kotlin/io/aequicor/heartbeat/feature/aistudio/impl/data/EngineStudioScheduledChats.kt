package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind

/**
 * Scheduled runs in studio chats: the chat is found by its native session, and the run keeps the chat's confirmed
 * model and approval over the defaults, like a worktree action. Records are read from the studio's chat store; all
 * changes go through the studio runtime.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioScheduledChats(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val repository: StudioRepository,
    private val runtime: StudioRuntime,
    private val runs: StudioRunCoordinator,
    private val host: StudioRunHost,
) : StudioScheduledChats {
    private val log = Log.tag("EngineStudioScheduledChats")
    private val store by lazy { stores.keyValue(ChatSpec) }

    override suspend fun chatOf(session: SessionRef): StudioScheduledChat? {
        log.d { "find the chat of a session" }
        return store.get(
            ChatsKey,
        ).orEmpty().firstOrNull { it.ref == session }?.let { StudioScheduledChat(it.id, it.projectId) }
    }

    override suspend fun sessionOf(chatId: String): SessionRef? {
        log.d { "read the session of a chat" }
        return store.get(ChatsKey).orEmpty().firstOrNull { it.id == chatId }?.ref
    }

    override suspend fun createHelperChat(projectId: String?, title: String): String {
        log.i { "create a helper conversation hasProject=${projectId != null}" }
        return repository.createSession(projectId, title, isWorktree = false).id
    }

    override suspend fun runScheduled(
        chatId: String,
        prompt: WakePrompt,
        target: EngineTarget?,
        onAccepted: suspend () -> Unit,
    ): RunOutcome {
        val record = checkNotNull(store.get(ChatsKey).orEmpty().firstOrNull { it.id == chatId }) {
            "Unknown studio conversation"
        }
        val settings = hostRunSettings(
            record,
            runtime.state.value.configurations[chatId]?.applied,
            runtime.defaults(),
            target,
        )
        log.i { "run a scheduled prompt in a studio conversation" }
        return runs.run(
            host,
            StudioTurnRequest(
                chatId,
                prompt.visible,
                settings,
                WorktreeRunKind.Coding,
                prompt.request,
                onAccepted = onAccepted,
                directives = listOf(prompt.directive),
            ),
            waitForIdle = true,
        )
    }
}

/**
 * Settings of a run the host starts in chat [record] (a worktree action, a scheduled wake): the chat's confirmed model
 * and approval ([applied] first, then the stored ones) over [defaults]; [target] is the route of a chat without one.
 */
internal fun hostRunSettings(
    record: StudioChatRecord,
    applied: StudioSessionSettings?,
    defaults: RunSettings,
    target: EngineTarget? = null,
): RunSettings {
    val confirmed = applied ?: record.configuration
    return defaults.copy(
        modelId = confirmed?.modelId ?: (record.target ?: target)?.studioModelId().orEmpty(),
        approval = confirmed?.approval ?: defaults.approval,
    )
}
