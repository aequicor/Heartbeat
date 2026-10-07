package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHistory
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionReader
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Listing exhausts one indexed snapshot; it does not imply complete native discovery. A publisher permit fences
 * the entire read against library/project changes, while every exposed session also requires its own permit.
 * History coverage, cursors and checkpoint pass through unchanged. Missing model metadata never selects defaults.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineHarnessSessionReader(
    private val facade: Lazy<EngineFacade>,
    private val access: HarnessSessionAccess,
) : HarnessSessionReader {
    override suspend fun list(owner: HarnessInstanceTarget): List<ScriptSession> {
        val publisher = publisher(owner)
        val visible = linkedMapOf<SessionRef, HarnessVisibleSession>()
        val cursors = mutableSetOf<SessionCursor>()
        var cursor: SessionCursor? = null
        do {
            check(access.isCurrent(owner) && publisher.isCurrent()) { "Harness session access was revoked" }
            currentCoroutineContext().ensureActive()
            val page = facade.value.sessions.page(request = PageRequest(cursor, limit = 500))
            for (summary in page.items) {
                check(access.isCurrent(owner)) { "Script activation is unavailable" }
                val route = access.session(summary.ref)
                val permit = access.permit(owner, HarnessTarget(summary.ref, route.workspace)) ?: continue
                visible[summary.ref] = HarnessVisibleSession(route, permit)
            }
            cursor = page.next
            check(cursor == null || cursors.add(cursor)) { "Session catalog cursor did not advance" }
        } while (cursor != null)
        for (entry in visible.values) {
            check(entry.permit.isCurrent()) { "Harness session access was revoked" }
        }
        check(publisher.isCurrent() && access.isCurrent(owner) && visible.values.all { it.route.isCurrent() }) {
            "Harness session snapshot was revoked"
        }
        currentCoroutineContext().ensureActive()
        return visible.values.map { entry ->
            val route = entry.route
            ScriptSession(route.captured.ref, route.captured.title, route.workspace, target = null)
        }
    }

    override suspend fun history(
        owner: HarnessInstanceTarget,
        session: SessionRef,
        page: HistoryPageRequest,
    ): ScriptHistory {
        val publisher = publisher(owner)
        val route = access.session(session)
        val permit = checkNotNull(access.permit(owner, HarnessTarget(session, route.workspace))) {
            "Session is unavailable to this harness"
        }
        check(permit.isCurrent() && access.isCurrent(owner) && route.isCurrent()) {
            "Harness session access was revoked"
        }
        val history = route.handle.features.resolve(SessionHistory)
        check(history is FeatureAccess.Available) { "Session history is unavailable" }
        val result = history.feature.page(page)
        check(permit.isCurrent() && publisher.isCurrent() && access.isCurrent(owner) && route.isCurrent()) {
            "Harness session access was revoked"
        }
        currentCoroutineContext().ensureActive()
        return ScriptHistory(session, result)
    }

    private suspend fun publisher(owner: HarnessInstanceTarget): HarnessDeliveryPermit {
        check(access.isCurrent(owner)) { "Script activation is unavailable" }
        return checkNotNull(access.permit(owner, null)) { "Harness session access is unavailable" }
    }
}

private data class HarnessVisibleSession(val route: HarnessSessionRoute, val permit: HarnessDeliveryPermit) {
    override fun toString(): String = "HarnessVisibleSession(***)"
}
