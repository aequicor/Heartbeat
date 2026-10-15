package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stored-ref probes cached per enabled engine set; a history write never reopens every other conversation.
 * A failure is cached only for an engine outside the enabled set, so it is not reported again on every recompute;
 * any other failure, including an adapter's transient `Unavailable`, is probed again on the next recompute.
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
            cache(enabled, ref, isResumable)
            isResumable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A disabled engine is decided by the enabled set, the cache key: probe and warn once per set.
            val isDisabled = ref.engine !in enabled
            log.w(e) { "Stored chat cannot currently be resumed engine=${ref.engine.value} cached=$isDisabled" }
            if (isDisabled) cache(enabled, ref, false)
            false
        }
    }

    /** A probe that outlived its enabled set is not cached under the next one. */
    private suspend fun cache(enabled: Set<EngineId>, ref: SessionRef, isResumable: Boolean) = lock.withLock {
        if (engines == enabled) byRef[ref] = isResumable
    }
}

/**
 * Catalog state that can change a failed probe's outcome: enablement, availability, bindings, observation and
 * preparation stage. Download progress changes many times a second and is collapsed into its stage.
 */
internal fun List<EngineInfo>.continuabilityKey(): List<EngineInfo> = map { info ->
    if (info.preparation is EnginePreparation.Downloading) info.copy(preparation = DownloadStage) else info
}

private val DownloadStage = EnginePreparation.Downloading(bytes = 0, total = null)

/** A closing or closed handle runs no more turns; its conversation continues through a resumed handle. */
internal fun ActiveSession.isReleased(): Boolean =
    state.value is ActiveSessionState.Closing || state.value == ActiveSessionState.Closed

/**
 * The open handle of conversation [id]. A runtime retired under an idle conversation (rotated key, engine restart)
 * closes its handle: the released handle is dropped, so the caller resumes the stored session instead of reusing it.
 */
internal suspend fun MutableMap<String, ActiveSession>.live(id: String): ActiveSession? {
    val handle = get(id) ?: return null
    if (!handle.isReleased()) return handle
    // Closing(failure) still owns the native attachment; close must succeed before a replacement is opened.
    if (handle.state.value is ActiveSessionState.Closing) handle.close()
    check(handle.state.value == ActiveSessionState.Closed) { "Native handle has not finished closing" }
    remove(id)
    releasedLog.i { "Released native handle dropped; the conversation resumes its stored session" }
    return null
}

private val releasedLog = Log.tag("StudioHandles")
