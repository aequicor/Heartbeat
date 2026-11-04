package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAdmission
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [EngineRouteAdmission] over the profile's canonical route resolver and runtime pool. The route is resolved
 * without adapter writes, and the pool owns the restricted acquisition and its cleanup; this service never
 * prepares, creates, reuses or promotes an ordinary runtime and never touches saved launch settings.
 */
class EngineRouteAdmissionService(
    private val routes: RouteResolver,
    private val pool: RuntimePool,
    private val context: FacadeContext,
) : EngineRouteAdmission {
    private val log = Log.tag("EngineRouteAdmission")

    override suspend fun assess(engine: EngineId, binding: EngineBindingId): RouteAdmission {
        log.i { "assess route engine=${engine.value} binding=${binding.value}" }
        val resolved = routes.resolveSession(engine, binding)
        val deadline = TimeSource.Monotonic.markNow() + ASSESSMENT_TIMEOUT
        val admission = withContext(context.io) {
            pool.assessAdmission(resolved, deadline) { routes.recheckSession(resolved) }
        }
        when (admission) {
            RouteAdmission.Unavailable -> log.i { "route admission unavailable engine=${engine.value}" }

            is RouteAdmission.Assessed -> log.i {
                "route admission assessed engine=${engine.value} status=${admission.decision.status} " +
                    "problems=${admission.decision.problems.size}"
            }
        }
        return admission
    }

    private companion object {
        /** Bounded UI wait for one restricted baseline, matching the session-opening allowance. */
        val ASSESSMENT_TIMEOUT = 30.seconds
    }
}
