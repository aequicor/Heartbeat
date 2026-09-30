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

/** Stored-ref probes cached per enabled engine set; a history write never reopens every other conversation. */
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
        val current = active(id)
        if (current != null) {
            return current.state.value !is ActiveSessionState.Closing &&
                current.state.value != ActiveSessionState.Closed
        }
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
