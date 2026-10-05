package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
    val modelId: String? = null,
    val isContinuable: Boolean = true,
    val isWorktree: Boolean = false,
    /** The conversation is an organic AI organism of the same id; the organism drives its sessions. */
    val isOrganism: Boolean = false,
    /** Native identity used to locate the existing conversation of a computer-use capture owner. */
    val nativeSession: SessionRef? = null,
    /** Route for observing native descendants without attaching an execution handle. */
    val treeAccess: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess? = null,
)

/** Projects and sessions shown in the sidebar. */
data class StudioWorkspace(val projects: List<StudioProject>, val sessions: List<StudioSession>) {
    /** The project of [id], if it still exists. */
    fun project(id: String?): StudioProject? = projects.firstOrNull { it.id == id }

    /** The session of [id], if it still exists. */
    fun session(id: String?): StudioSession? = sessions.firstOrNull { it.id == id }
}

/** Progress of a tool call made by the agent. */
enum class ToolRunStatus { Pending, Running, Done, Failed, Cancelled }

/** A command or an edit performed by the agent; [output] is literal console text, [diff] a unified diff. */
data class StudioToolRun(
    val id: String,
    val title: String,
    val status: ToolRunStatus = ToolRunStatus.Running,
    val output: String = "",
    val diff: String? = null,
    val feedback: FeedbackRecord? = null,
    val learning: StudioLearningCall? = null,
)

/** What a self-learning tool call does: saving an instruction or loading a learned skill. */
data class StudioLearningCall(
    val action: LearningAction,
    val kind: InstructionKind?,
    val title: String,
    val content: String,
)

/** Self-learning tool of a [StudioLearningCall]. */
enum class LearningAction { Remember, LoadSkill }

/** Ordered engine-visible answer content. Reasoning exists only when explicitly exposed by the engine. */
sealed interface StudioReplyPart {
    /** Stable identity within one native answer. */
    val id: String

    /** User-visible prose in its original position among tools. */
    data class Text(override val id: String, val text: String) : StudioReplyPart

    /** Reasoning text explicitly exposed by the engine. */
    data class Reasoning(override val id: String, val text: String) : StudioReplyPart

    /** A tool invocation updated in place as its output arrives. */
    data class Tool(val tool: StudioToolRun) : StudioReplyPart {
        override val id: String get() = tool.id
    }
}

/** One entry of a session transcript. */
sealed interface StudioMessage {
    /** Stable id within the session. */
    val id: String

    /** When the entry was written. */
    val createdAt: Instant

    /** False when the engine does not expose a timestamp for this historical entry. */
    val isTimestampKnown: Boolean get() = true

    /** A prompt sent by the user. */
    data class Prompt(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        override val isTimestampKnown: Boolean = true,
        val attachments: List<ResourceRef> = emptyList(),
    ) : StudioMessage

    /** An agent answer; streamed while [isStreaming]. [tools] keep their order of appearance. */
    data class Reply(
        override val id: String,
        override val createdAt: Instant,
        val text: String = "",
        val tools: List<StudioToolRun> = emptyList(),
        val isStreaming: Boolean = false,
        val parts: List<StudioReplyPart> = emptyList(),
        override val isTimestampKnown: Boolean = true,
    ) : StudioMessage

    /** The user stopped the run after [elapsed]. */
    data class Stopped(override val id: String, override val createdAt: Instant, val elapsed: Duration) : StudioMessage

    /** The run ended with an error of [kind]; details are in the log. */
    data class Failed(
        override val id: String,
        override val createdAt: Instant,
        val kind: RunFailureKind = RunFailureKind.Unknown,
    ) : StudioMessage
}

/** User-facing class of a failed run; native error text is never shown. */
enum class RunFailureKind { Limit, Context, Authentication, Network, Unknown }

/** A model offered by the studio composer. */
data class StudioModel(
    val id: String,
    val name: String,
    val isResearchSupported: Boolean = false,
    val isLocalProjectSupported: Boolean = false,
    val shortName: String = name,
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    /** Whether the engine applies the composer's approval mode to its own tool approvals. */
    val isTrustSupported: Boolean = false,
    /** Whether a running session of the engine changes its model in place. */
    val isModelSwitchSupported: Boolean = false,
    val inputSupport: PromptInputSupport = PromptInputSupport(),
)

/** Models the demo agent can impersonate, from the most capable to the fastest. */
val StudioModels: List<StudioModel> = listOf(
    StudioModel("pulse-pro", "Pulse Pro", isLocalProjectSupported = true),
    StudioModel("pulse", "Pulse", isLocalProjectSupported = true),
    StudioModel("pulse-mini", "Pulse Mini", isLocalProjectSupported = true),
)

/** Preferences of a fresh workspace. */
val DefaultRunSettings: RunSettings = RunSettings(
    modelId = StudioModels.first().id,
    effort = ReasoningEffort.High,
    approval = ApprovalMode.Ask,
)

/** Studio model id of an engine route: the exact route, so preferences never cross credentials. */
internal fun EngineTarget.studioModelId(): String = Json.encodeToString(EngineTarget.serializer(), this)

/** Engine route of a studio model id; null for scripted demo models, which are not JSON. */
internal fun studioModelTarget(id: String): EngineTarget? {
    if (!id.startsWith("{")) return null
    return try {
        Json.decodeFromString(EngineTarget.serializer(), id)
    } catch (e: SerializationException) {
        log.w(e) { "malformed studio model id" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "invalid studio model route" }
        null
    }
}

private val log = Log.tag("StudioModels")
