package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Human originals for questionnaire generation only; this snapshot never authorizes tool execution. */
sealed interface StudioQuestionnaireHumanContext {
    /** An original human send with its stable identity; never an instruction authorizing a tool. */
    data class Message(val request: RequestId, val text: String) {
        override fun toString(): String = "StudioQuestionnaireHumanContext.Message(request=$request)"
    }

    /** Bounded originals with no known gaps, suitable for generating and rechecking questionnaire answers. */
    data class Complete(val messages: List<Message>) : StudioQuestionnaireHumanContext {
        override fun toString(): String = "StudioQuestionnaireHumanContext.Complete(messages=${messages.size})"
    }

    /** Missing, revoked, conflicting or out-of-bounds originals cannot support automatic questionnaire answers. */
    data object Incomplete : StudioQuestionnaireHumanContext
}

/** Storage of studio projects, sessions and transcripts. Every write is visible to observers immediately. */
@Suppress("TooManyFunctions") // Authority reads belong to this backend and must default safely for every repository.
interface StudioRepository {
    /** Enabled engine, connection and model choices; ids identify the complete route. */
    fun observeModels(): Flow<List<StudioModel>> = flowOf(StudioModels)

    /** Projects and sessions, including archived ones. */
    fun observeWorkspace(): Flow<StudioWorkspace>

    /** Transcript of [sessionId] in chronological order; empty for unknown sessions. */
    fun observeMessages(sessionId: String): Flow<List<StudioMessage>>

    /**
     * Read-only snapshot of original human instructions captured at the host input boundary for this logical chat.
     * Missing provenance, unsupported backends and revoked authority return [TrustedUserInstructions.Incomplete].
     * Transcript prompts must never repair this snapshot. Callers must read it again before using a generated answer.
     */
    suspend fun userInstructions(sessionId: String): TrustedUserInstructions = TrustedUserInstructions.Incomplete

    /**
     * Human originals used only to generate questionnaire answers, independently of tool authority revocation.
     * Automatic answers may preserve this snapshot; manual substantive answers invalidate it. Unsupported backends
     * return [StudioQuestionnaireHumanContext.Incomplete]. Deliver generated answers only through
     * [admitQuestionnaireAnswer]; a separate read followed by delivery cannot exclude concurrent changes.
     */
    suspend fun questionnaireHumanContext(sessionId: String): StudioQuestionnaireHumanContext =
        StudioQuestionnaireHumanContext.Incomplete

    /**
     * Atomically admits a generated questionnaire answer only while the saved human context equals [expected].
     * Implementations compare the durable snapshot and invoke [admit] under the same mutex as every human-context
     * mutation, holding it until admission returns. A mismatch or unsupported backend returns false without invoking
     * [admit]. The result is true only when [admit] returns true; cancellation and failures propagate.
     *
     * [admit] may recheck lifecycle and dispatch the prepared answer to the questionnaire machine, whose generation
     * guards still apply. It must not generate model output, revoke authority, reenter this repository or wait for
     * answer collection: those operations belong outside the mutex. This guards saved context, not manual answers
     * in other batches whose invalidation has not yet reached storage.
     */
    suspend fun admitQuestionnaireAnswer(
        sessionId: String,
        expected: StudioQuestionnaireHumanContext.Complete,
        admit: suspend () -> Boolean,
    ): Boolean = false

    /** The project new sessions belong to by default. */
    suspend fun defaultProjectId(): String?

    /** Creates an empty session titled [title]. A backend overrides this form or the full one below. */
    suspend fun createSession(projectId: String?, title: String): StudioSession =
        createSession(projectId, title, isWorktree = false)

    /**
     * An isolated execution request or an organic AI organism ([organism]); demo backends support neither and never
     * silently fall back to an ordinary conversation. An organism chat is created only when its organism could be
     * conceived now, and its first prompt conceives it. The default delegates to the short form, so a backend must
     * override one of the two. [executionWorkspace] pins a hosted helper to an existing checkout without creating
     * another worktree; it requires a project and cannot accompany [isWorktree]. [isSandbox] selects source-only
     * isolation for a worktree; null retains the toggle-based choice for callers without an explicit selection.
     */
    suspend fun createSession(
        projectId: String?,
        title: String,
        isWorktree: Boolean,
        organism: OrganismRequest? = null,
        executionWorkspace: WorkspaceRef? = null,
        isSandbox: Boolean? = null,
    ): StudioSession {
        check(executionWorkspace == null) { "Fixed workspace execution is unavailable in this backend" }
        check(!isWorktree) { "Worktree execution is unavailable in this backend" }
        check(organism == null) { "Organic AI is unavailable in this backend" }
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

/** Maps native session references to the studio chats that own them, for hosted features addressing the chat. */
internal interface StudioChatResolver {
    /** The studio session whose native session is [ref], or null when no studio chat owns it. */
    suspend fun sessionIdOf(ref: SessionRef): String?

    /** Revokes durable originals and the exact live session before returning substantive questionnaire answers. */
    suspend fun invalidateUserInstructions(ref: SessionRef) {
        error("Human authority revocation is unavailable")
    }

    /**
     * Host-only revocation for batches containing only automatic answers and skips. Revoke durable tool authority
     * and the exact live session before returning; preserve only questionnaire context that is still complete
     * under the record lock. A concurrent manual invalidation must never be undone by an older automatic answer.
     * Backends without this distinction safely revoke both states through [invalidateUserInstructions].
     */
    suspend fun invalidateUserInstructionsForAutomaticQuestionnaire(ref: SessionRef) {
        invalidateUserInstructions(ref)
    }
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

/** Read-only live transcripts of native sessions the studio does not drive, such as the cells of an organism. */
fun interface StudioSessionViews {
    /**
     * The transcript of [ref], updated while it is collected. An engine that serves history only to open sessions
     * gets [ref] reopened with [reopening], the request its owner opened it with, so viewing never changes it.
     */
    fun observe(ref: SessionRef, reopening: ResumeSessionRequest): Flow<List<StudioMessage>>

    /** Read-only live state of the exact viewed session; null when no profile handle is open. */
    fun observation(ref: SessionRef): Flow<SessionObservationSnapshot?> = flowOf(null)

    /** Native family observation; unsupported backends must never report a confirmed empty family. */
    fun tree(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> =
        flowOf(SessionTreeSnapshot(root, coverage = SessionTreeCoverage.Unsupported))

    /** Native member history. This path must never fall back to resume. */
    fun observeNative(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): Flow<List<StudioMessage>> =
        flowOf(emptyList())
}

/** The workspace backend chosen once for the feature scope: engine-backed profile chats or the demo workspace. */
interface StudioBackend {
    /** Storage the screen observes. */
    suspend fun repository(): StudioRepository

    /** Views of foreign sessions; the demo workspace has none. */
    suspend fun sessionViews(): StudioSessionViews = StudioSessionViews { _, _ -> flowOf(emptyList()) }

    /** Handler of the studio machine effects. */
    suspend fun effects(): EffectHandler<AiStudioEffect, AiStudioIntent>
}

/**
 * The organism a new chat is created for: it grows from [goal] on [settings]. The harness choice of the composer
 * travels as the claim of [submissionId]; the chat keeps it from its creation on.
 */
data class OrganismRequest(val goal: String, val settings: RunSettings, val submissionId: String)

/** The organism [this] creation asks for, if it is one. */
internal fun AiStudioEffect.CreateSession.organism(): OrganismRequest? =
    OrganismRequest(prompt, settings, submissionId).takeIf { isOrganism }
