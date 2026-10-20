package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchFailure
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-helper barrier between request preparation and native route replacement; unrelated receipts stay available. */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioHelperRoutes {
    private val log = Log.tag("StudioHelperRoutes")
    private val lock = Mutex()
    private val routes = mutableMapOf<HelperId, Mutex>()

    suspend fun <T> withLock(helper: HelperId, body: suspend () -> T): T {
        val route = lock.withLock { routes.getOrPut(helper) { Mutex() } }
        return route.withLock { body() }
    }

    suspend fun <T> withIdleRoute(helper: HelperId, attempts: StudioHelperAttempts, body: suspend () -> T): T =
        withLock(helper) {
            log.v { "Reserve idle helper route" }
            refuseUnless(attempts.unresolved(helper) == null, StudioEngineSwitchFailure.Busy)
            body()
        }
}
