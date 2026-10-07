package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptEvent
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWake
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeSubmission
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** All external dependencies stay lazy until a published script calls a host operation. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineHarnessScheduler(
    private val runtime: Lazy<HarnessRuntime>,
    private val access: Lazy<HarnessSchedulerAccess>,
    private val facade: Lazy<EngineFacade>,
    private val bus: Lazy<SchedulerBus>,
    private val wakes: Lazy<HarnessWakePort>,
    private val operations: Lazy<HarnessWakeOperations>,
) : HarnessSchedulerHost {
    private val ownership = HarnessOwnedContext()

    override suspend fun wake(owner: HarnessInstanceTarget, request: HarnessScriptWake): WakeId {
        check(isCurrent(owner)) { "Script activation is unavailable" }
        val route = route(request.session)
        val permit = checkNotNull(permit(owner, HarnessTarget(request.session, route.workspace))) {
            "Session is unavailable to this harness"
        }
        val harness = owner.request.harness
        val wake = WakeRequest(
            WakeId("hw_" + Uuid.random().toHexString()),
            request.session,
            route.workspace,
            request.condition,
            request.note,
            WakeOrigin.Feature(HARNESS_WAKE_OWNER, harness.title),
            isDeduplicationRequired = true,
            ownerFeature = HARNESS_WAKE_OWNER,
            ownerContext = ownership.encode(harness.id, request.origin),
        )
        return operations.value.schedule(
            HarnessWakeSubmission(harness.id, wake, request.origin, request.at, isSend = false),
        ) { permit.isCurrent() && isCurrent(owner) && route.isCurrent() }
    }

    override suspend fun cancel(owner: HarnessInstanceTarget, id: WakeId, origin: HarnessCallOrigin): Boolean {
        if (!isCurrent(owner)) return false
        val permit = permit(owner, null) ?: return false
        val ready = wakes.value.snapshot()
        val request = ready?.wakes?.firstOrNull { it.id == id && it.id !in ready.delivering }?.request ?: return false
        val isOwned = request.ownerFeature == HARNESS_WAKE_OWNER &&
            ownership.decode(request.ownerContext)?.harness == owner.request.harness.id
        return if (isOwned && permit.isCurrent() && isCurrent(owner)) {
            currentCoroutineContext().ensureActive()
            wakes.value.cancel(request, origin(owner, origin))
        } else {
            false
        }
    }

    override suspend fun publish(owner: HarnessInstanceTarget, event: HarnessScriptEvent): EventKey {
        check(isCurrent(owner)) { "Script activation is unavailable" }
        require((event.payload?.length ?: 0) <= SchedulerLimits.MAX_PAYLOAD) { "Event payload is too long" }
        val permit = checkNotNull(permit(owner, null)) { "Harness publisher is unavailable" }
        val key = EventKeys.custom("harness.${owner.request.harness.name.value}.${event.name.value}")
        val origin = origin(owner, event.origin)
        check(permit.isCurrent() && isCurrent(owner)) { "Harness publisher was revoked" }
        currentCoroutineContext().ensureActive()
        bus.value.publish(key, origin, event.payload)
        return key
    }

    private fun isCurrent(owner: HarnessInstanceTarget): Boolean = owner.access.isActive &&
        runtime.value.publishedInstances.value.any { it === owner.access && it.request == owner.request }

    private suspend fun permit(owner: HarnessInstanceTarget, target: HarnessTarget?): HarnessDeliveryPermit? =
        withTimeoutOrNull(2.seconds) { access.value.permits(owner.request.harness.id, target).first() }

    private fun origin(owner: HarnessInstanceTarget, origin: HarnessCallOrigin): EventOrigin.Feature =
        EventOrigin.Feature(HARNESS_WAKE_OWNER, ownership.encode(owner.request.harness.id, origin))

    private suspend fun route(session: SessionRef): HarnessSessionRoute {
        val handle = facade.value.sessions.get(session)
        val summary = handle.summary.value
        check(summary.ref == session) { "Session identity changed" }
        val route = summary.lastRoute
        check(route == null || route.engine == session.engine) { "Session route is inconsistent" }
        check(route == null || summary.workspace == null || route.workspace == summary.workspace) {
            "Session workspace is inconsistent"
        }
        return HarnessSessionRoute(handle, summary, route?.workspace ?: summary.workspace)
    }
}

/** Only immutable routing metadata participates; title and observation updates do not revoke a submission. */
private data class HarnessSessionRoute(
    val handle: EngineSession,
    val captured: SessionSummary,
    val workspace: WorkspaceRef?,
) {
    fun isCurrent(): Boolean = handle.summary.value.let {
        it.ref == captured.ref && it.workspace == captured.workspace && it.lastRoute == captured.lastRoute
    }
    override fun toString(): String = "HarnessSessionRoute(***)"
}
