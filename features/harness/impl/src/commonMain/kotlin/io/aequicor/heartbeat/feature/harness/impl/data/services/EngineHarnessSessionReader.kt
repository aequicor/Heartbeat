package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Listing exhausts one indexed snapshot; it does not imply complete native discovery. A publisher permit fences
 * the entire read against library/project changes; one synchronous authority view decides every session, and a
 * fresh view rechecks the exposed ones. A session with unreadable or inconsistent routing is skipped, never fatal.
 * History coverage, cursors and checkpoint pass through unchanged. Missing model metadata never selects defaults.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineHarnessSessionReader(
    private val facade: Lazy<EngineFacade>,
    private val access: HarnessSessionAccess,
) : HarnessSessionReader {
    private val log = Log.tag("HarnessSessionReader")

    override suspend fun list(owner: HarnessInstanceTarget): List<ScriptSession> {
        val publisher = publisher(owner)
        val harness = owner.request.harness.id
        val view = access.snapshot()
        val visible = linkedMapOf<SessionRef, HarnessSessionRoute>()
        val cursors = mutableSetOf<SessionCursor>()
        var cursor: SessionCursor? = null
        do {
            check(access.isCurrent(owner) && publisher.isCurrent()) { "Harness session access was revoked" }
            currentCoroutineContext().ensureActive()
            val page = facade.value.sessions.page(request = PageRequest(cursor, limit = 500))
            for (summary in page.items) {
                check(access.isCurrent(owner)) { "Script activation is unavailable" }
                val route = route(summary.ref) ?: continue
                if (view.allows(harness, HarnessTarget(summary.ref, route.workspace)) == true) {
                    visible[summary.ref] = route
                }
            }
            cursor = page.next
            check(cursor == null || cursors.add(cursor)) { "Session catalog cursor did not advance" }
        } while (cursor != null)
        val current = access.snapshot()
        check(visible.values.all { current.allows(harness, HarnessTarget(it.captured.ref, it.workspace)) == true }) {
            "Harness session access was revoked"
        }
        check(publisher.isCurrent() && access.isCurrent(owner) && visible.values.all { it.isCurrent() }) {
            "Harness session snapshot was revoked"
        }
        currentCoroutineContext().ensureActive()
        return visible.values.map { route ->
            ScriptSession(route.captured.ref, route.captured.title, route.workspace, target = null)
        }
    }

    private suspend fun route(session: SessionRef): HarnessSessionRoute? = try {
        access.session(session)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Skip a session with unavailable routing from a harness listing" }
        null
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
