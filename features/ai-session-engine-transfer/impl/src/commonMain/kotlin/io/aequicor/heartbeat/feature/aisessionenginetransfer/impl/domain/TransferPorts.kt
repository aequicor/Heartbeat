package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.LogicalConversation
import kotlin.time.Instant

/** Whether transfers may run now; reads toggles on every call. */
internal fun interface TransferGate {
    suspend fun isEnabled(): Boolean
}

/**
 * Chronological tail of a stored session. [isTruncated] means older items exist but were not read;
 * [coverage] reports whether the native store itself is missing data.
 */
internal data class Transcript(val items: List<SessionItem>, val coverage: HistoryCoverage, val isTruncated: Boolean)

/** Read-only access to stored session history; failures are domain exceptions, never an empty transcript. */
internal fun interface SessionTranscripts {
    /** Reads the newest items of [source] until roughly [budgetChars] of text is collected. */
    suspend fun read(source: SessionRef, budgetChars: Int): Transcript
}

/** Creates target sessions through an explicit engine route, without any credential fallback. */
internal fun interface EngineSessions {
    suspend fun create(target: EngineTarget, workspace: WorkspaceRef?): SeedSession
}

/** Freshly created session handle used only to deliver the handoff. */
internal interface SeedSession {
    /** Persistent native address of the created session. */
    val ref: SessionRef

    /** Returns after native acceptance; ambiguous delivery throws Request.OutcomeUnknown. */
    suspend fun send(prompt: PromptRequest)

    /** Releases the handle; the accepted handoff turn keeps running in the profile runtime. */
    suspend fun close()
}

/** Profile-owned storage of logical conversations. */
internal interface ConversationJournal {
    suspend fun get(id: ConversationId): LogicalConversation?

    suspend fun put(conversation: LogicalConversation)

    suspend fun remove(id: ConversationId)
}

/** Source of fresh opaque identifiers and segment timestamps. */
internal interface TransferStamps {
    fun conversation(): ConversationId

    fun request(): RequestId

    fun now(): Instant
}
