package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select

/** Wins over the scheduler's engine-facade fallback for every session that has a studio chat. */
internal const val STUDIO_HOST_PRIORITY: Int = 100

/** A studio chat that owns a native session. */
internal data class StudioScheduledChat(val id: String, val projectId: String?)

/** The studio's side of scheduled runs. */
internal interface StudioScheduledChats {
    /** The chat whose native session is [session], or null. */
    suspend fun chatOf(session: SessionRef): StudioScheduledChat?

    /** The native session of chat [chatId], or null before its first turn opened one. */
    suspend fun sessionOf(chatId: String): SessionRef?

    /** Creates an empty chat in [projectId] (null: without a project) and returns its id. */
    suspend fun createHelperChat(projectId: String?, title: String): String

    /**
     * Runs [prompt] as the next turn of chat [chatId], waiting while it is busy; [target] is the route of a chat that
     * has none yet. [onAccepted] runs once the engine accepted the turn.
     */
    suspend fun runScheduled(
        chatId: String,
        prompt: WakePrompt,
        target: EngineTarget?,
        onAccepted: suspend () -> Unit,
    ): RunOutcome
}

/**
 * Delivers scheduler wakes into studio chats, so a woken turn appears in the transcript like any other, waits for a
 * busy chat and keeps the chat's model and approval. Helper agents get their own chat in the parent's project. The
 * turn runs in the profile scope; this host returns as soon as the engine accepted it.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioScheduledSessionHost(
    // Lazy: the studio runtime depends on the hosted tools, whose background actions depend on these hosts.
    private val scheduledChats: Lazy<StudioScheduledChats>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ScheduledSessionHost {
    private val log = Log.tag("StudioScheduledSessionHost")
    private val chats: StudioScheduledChats get() = scheduledChats.value

    override val priority: Int = STUDIO_HOST_PRIORITY

    override suspend fun owns(session: SessionRef): Boolean = chats.chatOf(session) != null

    override suspend fun wake(request: WakeRequest, prompt: WakePrompt) {
        val chat = checkNotNull(chats.chatOf(request.session)) { "The studio chat of the session is gone" }
        log.i { "wake a studio conversation" }
        submit(chat.id, prompt, target = null)
    }

    override suspend fun spawn(request: SpawnRequest): SessionRef {
        val projectId = chats.chatOf(request.parent)?.projectId ?: request.workspace?.value
        val chat = chats.createHelperChat(projectId, request.title)
        log.i { "start a helper conversation hasProject=${projectId != null}" }
        submit(chat, request.prompt, request.target)
        return checkNotNull(chats.sessionOf(chat)) { "The helper conversation has no session" }
    }

    /** Starts the run in the profile and waits only for acceptance; a run that ends without it is a failure. */
    private suspend fun submit(chat: String, prompt: WakePrompt, target: EngineTarget?) {
        val accepted = CompletableDeferred<Unit>()
        val run = profile.coroutineScope.async {
            chats.runScheduled(chat, prompt, target) {
                accepted.complete(Unit)
            }
        }
        val outcome = select {
            accepted.onAwait { null }
            run.onAwait { it }
        }
        check(
            outcome == null || accepted.isCompleted,
        ) { "The scheduled prompt was not accepted: ${outcome?.name.orEmpty()}" }
    }
}
