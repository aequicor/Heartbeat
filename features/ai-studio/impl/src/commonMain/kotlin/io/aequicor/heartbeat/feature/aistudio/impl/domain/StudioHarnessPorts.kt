package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A harness available in a chat's composer, optionally selected by default for a new user chat. */
data class StudioHarness(
    val id: String,
    val title: String,
    val description: String = "",
    val projectIds: Set<String> = emptySet(),
    val isDefaultForNewSessions: Boolean = false,
) {
    /** Empty scope is shared; project restrictions also limit where the new-chat default is offered. */
    fun isAvailableFor(projectId: String?): Boolean = projectIds.isEmpty() || projectId in projectIds
}

/** Library choices: enabled harnesses and committed connections; unknown initial data cannot seed a new chat. */
data class StudioHarnessChoices(
    val harnesses: List<StudioHarness> = emptyList(),
    val attached: Map<SessionRef, Set<String>> = emptyMap(),
    val isReady: Boolean = true,
    val isLoadFailed: Boolean = false,
)

/**
 * Chat side of the harness library; implemented over the harness machine, absent without the feature.
 * A new chat's choice stays on the screen until its submission: [claim] hands it over under the submission id,
 * which travels with the submission to its first turn, and [StudioHarnessAttach] connects it before that prompt.
 * The screen only claims; the turn uses and releases the claim (a new organism chat reads it when it is created and
 * releases it with its first submission), so leaving the screen never strands it.
 */
interface StudioHarnesses {
    val choices: Flow<StudioHarnessChoices>

    /** Retries the initial library load without changing the draft. */
    suspend fun reload(): Boolean = false

    /** Connects or disconnects an existing chat; true once the library confirmed the change. */
    suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean): Boolean

    /** A new chat's submission carries [harnesses]; called before the submission is sent. */
    fun claim(submissionId: String, harnesses: Set<String>)

    /** The submission was not sent; its claim is dropped. */
    fun release(submissionId: String)

    /** Without the harness feature nothing is offered. */
    object None : StudioHarnesses {
        override val choices: Flow<StudioHarnessChoices> = flowOf(StudioHarnessChoices())
        override suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean) = false
        override fun claim(submissionId: String, harnesses: Set<String>) = Unit
        override fun release(submissionId: String) = Unit
    }
}

/** Choices made before a session exists, by tool contribution group ([CreateSessionRequest.preselected]). */
internal typealias ToolChoices = Map<String, Set<String>>

/** Connects a new chat's chosen harnesses between opening its session and submitting the first prompt. */
internal interface StudioHarnessAttach {
    /**
     * The claim of [submissionId] as [CreateSessionRequest.preselected] choices, without using it: an engine that
     * declares tools when it opens the session (Codex) already sees the chosen harnesses, and a new organism chat
     * takes its harness choice from the harness tools group entry when it is created.
     */
    fun preselected(submissionId: String): ToolChoices

    /** Connects the harnesses claimed by [submissionId] to [session]; a claim is used once, other turns pass. */
    suspend fun beforeSubmit(submissionId: String, session: SessionRef)

    /** Committed connections, including disabled harnesses. Null only while the whole feature is off. */
    suspend fun attached(session: SessionRef): Set<String>?

    /** Restores all carried connections before a replacement native session may receive a prompt. */
    suspend fun restore(harnesses: Set<String>, session: SessionRef)

    /** The turn of [submissionId] ended; a claim it did not use is dropped. */
    fun release(submissionId: String)
}

/** Default without the harness feature. */
internal object NoStudioHarnessAttach : StudioHarnessAttach {
    override fun preselected(submissionId: String): ToolChoices = emptyMap()
    override suspend fun beforeSubmit(submissionId: String, session: SessionRef) = Unit
    override suspend fun attached(session: SessionRef): Set<String> = emptySet()
    override suspend fun restore(harnesses: Set<String>, session: SessionRef) {
        check(harnesses.isEmpty()) { "Carried harness connections are unavailable" }
    }
    override fun release(submissionId: String) = Unit
}
