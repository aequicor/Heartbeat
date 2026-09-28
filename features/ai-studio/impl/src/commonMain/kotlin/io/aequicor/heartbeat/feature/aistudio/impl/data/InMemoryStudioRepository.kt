package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock

/**
 * In-memory demo workspace, used while [io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime] is off
 * and by isolated screen and agent tests. Engine-backed conversations are stored by [EngineStudioRepository].
 * Logs entry types and sizes, never conversation content or identifiers.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@Inject
internal class InMemoryStudioRepository(private val clock: Clock) : StudioRepository {
    private val log = Log.tag("StudioRepository")
    private val data = MutableStateFlow(studioSeed(clock.now()))
    private val ids = AtomicInt(0)

    override fun observeWorkspace(): Flow<StudioWorkspace> {
        log.d { "observeWorkspace" }
        return data.map { StudioWorkspace(it.projects, it.sessions) }.distinctUntilChanged()
    }

    override fun observeMessages(sessionId: String): Flow<List<StudioMessage>> {
        log.d { "observeMessages" }
        return data.map { it.messages[sessionId].orEmpty() }.distinctUntilChanged()
    }

    override suspend fun defaultProjectId(): String? {
        log.d { "defaultProjectId" }
        val projects = data.value.projects
        return projects.firstOrNull { it.id == DEFAULT_PROJECT_ID }?.id ?: projects.firstOrNull()?.id
    }

    override suspend fun createSession(projectId: String?, title: String): StudioSession {
        val session = StudioSession("session-${ids.incrementAndFetch()}", projectId, title, clock.now())
        data.update { it.copy(sessions = it.sessions + session, messages = it.messages + (session.id to emptyList())) }
        log.i { "conversation created inProject=${projectId != null} titleLength=${title.length}" }
        return session
    }

    override suspend fun append(sessionId: String, message: StudioMessage) {
        data.update { current ->
            val sessions = if (message is StudioMessage.Prompt) {
                current.sessions.map { if (it.id == sessionId) it.copy(updatedAt = message.createdAt) else it }
            } else {
                current.sessions
            }
            val transcript = current.messages[sessionId].orEmpty() + message
            current.copy(sessions = sessions, messages = current.messages + (sessionId to transcript))
        }
        log.d { "message appended id=${message.id} type=${message::class.simpleName.orEmpty()}" }
    }

    override suspend fun replace(sessionId: String, message: StudioMessage) {
        data.update { current ->
            val transcript = current.messages[sessionId].orEmpty().map { if (it.id == message.id) message else it }
            current.copy(messages = current.messages + (sessionId to transcript))
        }
        log.v { "message replaced id=${message.id}" }
    }

    override suspend fun edit(sessionId: String, edit: SessionEdit) {
        data.update { current ->
            current.copy(sessions = current.sessions.map { if (it.id == sessionId) it.edited(edit) else it })
        }
        log.i { "conversation edited change=${edit::class.simpleName.orEmpty()}" }
    }

    override suspend fun setBranch(sessionId: String, branch: String) {
        data.update { current ->
            current.copy(sessions = current.sessions.map { if (it.id == sessionId) it.copy(branch = branch) else it })
        }
        log.i { "conversation branch recorded" }
    }

    override fun newMessageId(): String {
        log.v { "newMessageId" }
        return "message-${ids.incrementAndFetch()}"
    }
}

/** Snapshot of the in-memory studio storage. */
internal data class StudioData(
    val projects: List<StudioProject>,
    val sessions: List<StudioSession>,
    val messages: Map<String, List<StudioMessage>>,
)

private fun StudioSession.edited(edit: SessionEdit): StudioSession = when (edit) {
    is SessionEdit.Rename -> copy(title = edit.title)
    is SessionEdit.SetPinned -> copy(isPinned = edit.isPinned)
    is SessionEdit.SetUnread -> copy(isUnread = edit.isUnread)
    is SessionEdit.SetArchived -> copy(isArchived = edit.isArchived, isPinned = isPinned && !edit.isArchived)
}
