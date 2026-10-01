package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stored-ref probes cached per enabled engine set; a history write never reopens every other conversation.
 * A live handle makes its conversation continuable; a released one (its runtime was retired or restarted) does not
 * end the conversation: the next send resumes the stored session, so the stored reference decides.
 */
internal class StudioContinuability(
    private val facade: EngineFacade,
    private val active: suspend (String) -> ActiveSession?,
) {
    private val log = Log.tag("StudioContinuability")
    private val lock = Mutex()
    private var engines: Set<EngineId>? = null
    private val byRef = mutableMapOf<SessionRef, Boolean>()

    suspend fun isContinuable(id: String, ref: SessionRef?): Boolean {
        if (ref == null) return true
        if (active(id)?.isReleased() == false) return true
        val enabled = facade.engines.state.value.mapTo(mutableSetOf()) { it.descriptor.id }
        lock.withLock {
            if (engines != enabled) {
                engines = enabled
                byRef.clear()
            }
            byRef[ref]?.let { return it }
        }
        return try {
            val isResumable = facade.sessions.get(ref).features.resolve(ResumesSessions) is FeatureAccess.Available
            lock.withLock { byRef[ref] = isResumable }
            isResumable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Stored chat cannot currently be resumed" }
            false
        }
    }
}

/** A closing or closed handle runs no more turns; its conversation continues through a resumed handle. */
internal fun ActiveSession.isReleased(): Boolean =
    state.value is ActiveSessionState.Closing || state.value == ActiveSessionState.Closed

/**
 * The open handle of conversation [id]. A runtime retired under an idle conversation (rotated key, engine restart)
 * closes its handle: the released handle is dropped, so the caller resumes the stored session instead of reusing it.
 */
internal fun MutableMap<String, ActiveSession>.live(id: String): ActiveSession? {
    val handle = get(id) ?: return null
    if (!handle.isReleased()) return handle
    remove(id)
    releasedLog.i { "Released native handle dropped; the conversation resumes its stored session" }
    return null
}

private val releasedLog = Log.tag("StudioHandles")
