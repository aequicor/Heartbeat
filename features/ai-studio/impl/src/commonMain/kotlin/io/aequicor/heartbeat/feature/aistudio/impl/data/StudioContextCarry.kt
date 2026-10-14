package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import kotlinx.coroutines.CancellationException

/**
 * Carries a studio conversation across changes of its native context, under the chat's run reservation and
 * conversation lock. The repository supplies its storage: the record and the items of a conversation, their writes,
 * and its open native [handles].
 */
internal class StudioContextCarry(
    private val locks: StudioConversationLocks,
    private val record: suspend (String) -> StudioChatRecord,
    private val items: suspend (String) -> List<SessionItem>,
    private val write: suspend (String, (List<SessionItem>) -> List<SessionItem>) -> Unit,
    private val update: suspend (String, StudioChatRecord.() -> StudioChatRecord) -> Unit,
    private val handles: StudioCarriedHandles,
) {
    private val log = Log.tag("StudioContextCarry")

    /**
     * Freezes the transcript and detaches the native session of [request]; the next turn creates a new session and
     * receives the essence of this one, or its continuation brief. The old native session is kept. An outside
     * environment supplies [expected] to require that the same proven idle handle still owns the conversation.
     */
    suspend fun rollover(
        request: StudioTurnRequest,
        expected: ActiveSession? = null,
        harnesses: Set<String>? = null,
        harnessSource: SessionRef? = null,
    ): RunOutcome = locks.withLock(request.id) {
        val id = request.id
        val record = record(id).takeIf { it.isRolloverAllowed } ?: return@withLock RunOutcome.Failed
        val items = items(id)
        if (expected != null) {
            check(record.ref == expected.ref && handles.handle(id) === expected && expected.canCarryEnvironment()) {
                "The native session changed before its outside context could be carried"
            }
        }
        val seed = if (request.isBriefed) continuationSeed(items, request.isSummarized) else rolloverSeed(items)
        write(id) { it.frozen(record.contextSegment + 1) }
        // A briefed rollover ran the handoff summary turn last: its failure is not the chat's.
        update(id) {
            rolledOver(seed).copy(
                pendingHarnesses = harnesses ?: pendingHarnesses,
                pendingHarnessSource = pendingHarnessSource ?: harnessSource,
            ).let { if (request.isBriefed) it.withoutFailure() else it }
        }
        detach(id)
        RunOutcome.Completed
    }

    /** Outside context changes retain harness choices, including unresolved ones while the feature is off. */
    suspend fun environment(
        request: StudioTurnRequest,
        active: ActiveSession,
        harnesses: StudioHarnessAttach,
        refresh: suspend () -> Unit,
    ) {
        check(active.canCarryEnvironment()) { "The previous native turn is still active or uncertain" }
        val current = record(request.id)
        val source = current.pendingHarnessSource ?: active.ref
        val attached = current.pendingHarnesses ?: harnesses.attached(source)
        refresh()
        check(current.pendingHarnesses != null || harnesses.attached(source) == attached) {
            "Harness connections changed while preparing carry"
        }
        check(rollover(request, active, attached, source) == RunOutcome.Completed) {
            "This chat cannot replace its native context"
        }
    }

    /** Resolves deferred choices before native declarations once the feature is enabled; otherwise keeps the source. */
    suspend fun prepareHarnesses(id: String, harnesses: StudioHarnessAttach): StudioChatRecord {
        val current = record(id)
        val source = current.pendingHarnessSource ?: return current
        if (current.pendingHarnesses != null) return current
        val selected = harnesses.attached(source) ?: return current
        update(id) { copy(pendingHarnesses = selected) }
        return record(id)
    }

    /** Restores before the next enabled prompt; failure or a disabled feature leaves the durable receipt intact. */
    suspend fun restoreHarnesses(id: String, session: SessionRef, harnesses: StudioHarnessAttach) {
        prepareHarnesses(id, harnesses).restoreCarriedHarnesses(session, harnesses) { pending ->
            update(id) {
                check(ref == session && pendingHarnesses == pending) { "Carried harness ownership changed" }
                copy(pendingHarnesses = null, pendingHarnessSource = null, pendingHarnessContext = null)
            }
        }
    }

    /**
     * Closes the native handle of the session the chat left. The rollover has already taken effect, so a failed close
     * keeps the handle, now closing, for the retried close the next open runs before a new session is created.
     */
    private suspend fun detach(id: String) {
        val active = handles.handle(id) ?: return
        try {
            active.close()
            handles.forget(id, active)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Previous native session could not be closed after a rollover; its close is retried on open" }
        }
    }

    /**
     * The engine replaced its context with its own summary; the next turn restores what the summary may have lost. The
     * brief is best effort: the compaction already succeeded, so a failure here only leaves the next turn without it.
     */
    suspend fun brief(id: String) = locks.withLock(id) {
        try {
            val brief = compactionBrief(items(id))
            update(id) { copy(pendingBrief = brief) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "continuation brief after a native compaction could not be stored" }
        }
    }
}

/** The open native handles of studio conversations, as the context carry sees them. */
internal interface StudioCarriedHandles {
    /** The open native handle of conversation [id], if any. */
    suspend fun handle(id: String): ActiveSession?

    /** Drops [closed] from conversation [id] once it is closed; a newer handle is kept. */
    suspend fun forget(id: String, closed: ActiveSession)
}
