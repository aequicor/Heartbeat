package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import kotlin.time.Duration
import kotlin.time.Instant

/** Where the agent of a project executes its commands. */
enum class StudioEnvironment { Local, Cloud }

/** A code base the agent works in; [branch] is the checked-out branch new sessions start from. */
data class StudioProject(val id: String, val name: String, val environment: StudioEnvironment, val branch: String)

/**
 * A conversation with the agent. [branch] is set once the agent pushed its changes to a branch.
 * [updatedAt] orders recent sessions; it changes when a prompt is sent, not on every streamed chunk.
 */
data class StudioSession(
    val id: String,
    val projectId: String?,
    val title: String,
    val updatedAt: Instant,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val isArchived: Boolean = false,
    val branch: String? = null,
)

/** Projects and sessions shown in the sidebar. */
data class StudioWorkspace(val projects: List<StudioProject>, val sessions: List<StudioSession>) {
    /** The project of [id], if it still exists. */
    fun project(id: String?): StudioProject? = projects.firstOrNull { it.id == id }

    /** The session of [id], if it still exists. */
    fun session(id: String?): StudioSession? = sessions.firstOrNull { it.id == id }
}

/** Progress of a tool call made by the agent. */
enum class ToolRunStatus { Running, Done, Failed }

/** A command or an edit performed by the agent; [output] is literal console text, [diff] a unified diff. */
data class StudioToolRun(
    val id: String,
    val title: String,
    val status: ToolRunStatus = ToolRunStatus.Running,
    val output: String = "",
    val diff: String? = null,
)

/** One entry of a session transcript. */
sealed interface StudioMessage {
    /** Stable id within the session. */
    val id: String

    /** When the entry was written. */
    val createdAt: Instant

    /** A prompt sent by the user. */
    data class Prompt(override val id: String, override val createdAt: Instant, val text: String) : StudioMessage

    /** An agent answer; streamed while [isStreaming]. [tools] keep their order of appearance. */
    data class Reply(
        override val id: String,
        override val createdAt: Instant,
        val text: String = "",
        val tools: List<StudioToolRun> = emptyList(),
        val isStreaming: Boolean = false,
    ) : StudioMessage

    /** The user stopped the run after [elapsed]. */
    data class Stopped(override val id: String, override val createdAt: Instant, val elapsed: Duration) : StudioMessage

    /** The run ended with an error; details are in the log. */
    data class Failed(override val id: String, override val createdAt: Instant) : StudioMessage
}

/** A model offered by the studio composer. */
data class StudioModel(val id: String, val name: String)

/** Models the demo agent can impersonate, from the most capable to the fastest. */
val StudioModels: List<StudioModel> = listOf(
    StudioModel("pulse-pro", "Pulse Pro"),
    StudioModel("pulse", "Pulse"),
    StudioModel("pulse-mini", "Pulse Mini"),
)

/** Preferences of a fresh workspace. */
val DefaultRunSettings: RunSettings = RunSettings(
    modelId = StudioModels.first().id,
    effort = ReasoningEffort.High,
    approval = ApprovalMode.Ask,
)
