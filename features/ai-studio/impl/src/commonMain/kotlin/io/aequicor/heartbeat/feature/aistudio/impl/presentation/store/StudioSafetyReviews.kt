package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewLoad
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewMonitor
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SafetyReviewsUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Mirrors the profile's automatic safety review load into the sidebar header, so the user sees how many reviews run
 * and wait while agents of several chats and projects work. Display only: queueing stays with the reviewer; the
 * indicator is hidden while [visibility] is off.
 */
internal class StudioSafetyReviews(
    private val monitor: AgentSafetyReviewMonitor,
    private val visibility: Flow<Boolean>,
) {
    suspend fun observe(pipeline: StudioPipeline) = with(pipeline) {
        combine(visibility, monitor.load) { shown, load -> if (shown) load.toUi() else null }
            .distinctUntilChanged()
            .collect { reviews -> updateState { copy(safetyReviews = reviews) } }
    }
}

internal fun AgentSafetyReviewLoad.toUi(): SafetyReviewsUi? =
    if (isIdle) null else SafetyReviewsUi(capacity, running, queued, finishing, abandoned)
