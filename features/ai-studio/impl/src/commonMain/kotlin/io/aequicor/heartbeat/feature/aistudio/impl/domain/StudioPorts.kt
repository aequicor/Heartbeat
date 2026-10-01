package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Storage of studio projects, sessions and transcripts. Every write is visible to observers immediately. */
interface StudioRepository {
    /** Enabled engine, connection and model choices; ids identify the complete route. */
    fun observeModels(): Flow<List<StudioModel>> = flowOf(StudioModels)

    /** Projects and sessions, including archived ones. */
    fun observeWorkspace(): Flow<StudioWorkspace>

    /** Transcript of [sessionId] in chronological order; empty for unknown sessions. */
    fun observeMessages(sessionId: String): Flow<List<StudioMessage>>

    /** The project new sessions belong to by default. */
    suspend fun defaultProjectId(): String?

    /** Creates an empty session titled [title]. */
    suspend fun createSession(projectId: String?, title: String): StudioSession

    /** An isolated execution request; demo backends never silently fall back to the source checkout. */
    suspend fun createSession(projectId: String?, title: String, isWorktree: Boolean): StudioSession {
        check(!isWorktree) { "Worktree execution is unavailable in this backend" }
        return createSession(projectId, title)
    }

    /** Adds [message] to the end of the transcript; prompts also move the session to the top of recents. */
    suspend fun append(sessionId: String, message: StudioMessage)

    /** Replaces the transcript entry with the same id, e.g. a streamed reply. */
    suspend fun replace(sessionId: String, message: StudioMessage)

    /** Applies a metadata change requested by the user. */
    suspend fun edit(sessionId: String, edit: SessionEdit)

    /** Remembers the branch the agent pushed the session's changes to. */
    suspend fun setBranch(sessionId: String, branch: String)

    /** A new id for a transcript entry. */
    fun newMessageId(): String
}

/** Input of one agent run: the new [prompt] after the existing [history] of the session. */
data class AgentRequest(
    val prompt: String,
    val history: List<StudioMessage>,
    val settings: RunSettings,
    val project: StudioProject?,
)

/** Incremental output of an agent run. */
sealed interface AgentEvent {
    /** Appends [text] to the answer. */
    data class Text(val text: String) : AgentEvent

    /** A tool call [id] started. */
    data class ToolStarted(val id: String, val title: String) : AgentEvent

    /** Console [output] of the tool call [id]. */
    data class ToolOutput(val id: String, val output: String) : AgentEvent

    /** The tool call [id] ended; [title] describes the result, [diff] the changed files. */
    data class ToolFinished(val id: String, val title: String, val isSuccess: Boolean, val diff: String? = null) :
        AgentEvent

    /** The agent pushed its changes to [name]. */
    data class BranchCreated(val name: String) : AgentEvent
}

/** Executes prompts. Cancelling the collection stops the agent. */
fun interface StudioAgent {
    /** Streams the reply to [request]; errors end the flow exceptionally. */
    fun run(request: AgentRequest): Flow<AgentEvent>
}

/** Whether the studio workspace is switched on. */
interface StudioAvailability {
    /** Reads the effective feature toggle. */
    suspend fun isEnabled(): Boolean

    /** The effective toggle value now and after every change. */
    fun observe(): Flow<Boolean>
}

/** The workspace backend chosen once for the feature scope: engine-backed profile chats or the demo workspace. */
interface StudioBackend {
    /** Storage the screen observes. */
    suspend fun repository(): StudioRepository

    /** Handler of the studio machine effects. */
    suspend fun effects(): EffectHandler<AiStudioEffect, AiStudioIntent>
}
