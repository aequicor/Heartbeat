package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptEvent
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWake
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionSender
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeSubmission
import io.aequicor.heartbeat.feature.harness.impl.domain.services.forSend
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** All external dependencies stay lazy until a published script calls a host operation. */
@ContributesBinding(ProfileScope::class, binding = binding<HarnessSchedulerHost>())
@ContributesBinding(ProfileScope::class, binding = binding<HarnessSessionSender>())
@Inject
internal class EngineHarnessScheduler(
    private val sessions: HarnessSessionAccess,
    private val bus: Lazy<SchedulerBus>,
    private val wakes: Lazy<HarnessWakePort>,
    private val operations: Lazy<HarnessWakeOperations>,
    private val clock: Clock,
) : HarnessSchedulerHost,
    HarnessSessionSender {
    private val ownership = HarnessOwnedContext()

    override suspend fun wake(owner: HarnessInstanceTarget, request: HarnessScriptWake): WakeId =
        submit(owner, request, isSend = false)

    override suspend fun send(
        owner: HarnessInstanceTarget,
        session: SessionRef,
        text: String,
        origin: HarnessCallOrigin,
    ): WakeId {
        val inherited = origin.forSend(owner.request.harness.id)
        val request = HarnessScriptWake(session, WakeCondition(deadline = clock.now()), text, inherited)
        return submit(owner, request, isSend = true)
    }

    private suspend fun submit(owner: HarnessInstanceTarget, request: HarnessScriptWake, isSend: Boolean): WakeId {
        check(sessions.isCurrent(owner)) { "Script activation is unavailable" }
        val route = sessions.session(request.session)
        val permit = checkNotNull(sessions.permit(owner, HarnessTarget(request.session, route.workspace))) {
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
            isNoteVisible = isSend,
            ownerFeature = HARNESS_WAKE_OWNER,
            ownerContext = ownership.encode(harness.id, request.origin),
        )
        return operations.value.schedule(
            HarnessWakeSubmission(harness.id, wake, request.origin, isSend),
        ) { permit.isCurrent() && sessions.isCurrent(owner) && route.isCurrent() }
    }

    override suspend fun cancel(owner: HarnessInstanceTarget, id: WakeId, origin: HarnessCallOrigin): Boolean {
        if (!sessions.isCurrent(owner)) return false
        val permit = sessions.permit(owner, null) ?: return false
        val ready = wakes.value.snapshot()
        val request = ready?.wakes?.firstOrNull { it.id == id && it.id !in ready.delivering }?.request ?: return false
        val isOwned = request.ownerFeature == HARNESS_WAKE_OWNER &&
            ownership.decode(request.ownerContext)?.harness == owner.request.harness.id
        return if (isOwned && permit.isCurrent() && sessions.isCurrent(owner)) {
            currentCoroutineContext().ensureActive()
            wakes.value.cancel(request, origin(owner, origin))
        } else {
            false
        }
    }

    override suspend fun publish(owner: HarnessInstanceTarget, event: HarnessScriptEvent): EventKey {
        check(sessions.isCurrent(owner)) { "Script activation is unavailable" }
        require((event.payload?.length ?: 0) <= SchedulerLimits.MAX_PAYLOAD) { "Event payload is too long" }
        val permit = checkNotNull(sessions.permit(owner, null)) { "Harness publisher is unavailable" }
        val key = EventKeys.custom("harness.${owner.request.harness.name.value}.${event.name.value}")
        val origin = origin(owner, event.origin)
        check(permit.isCurrent() && sessions.isCurrent(owner)) { "Harness publisher was revoked" }
        currentCoroutineContext().ensureActive()
        bus.value.publish(key, origin, event.payload)
        return key
    }

    private fun origin(owner: HarnessInstanceTarget, origin: HarnessCallOrigin): EventOrigin.Feature =
        EventOrigin.Feature(HARNESS_WAKE_OWNER, ownership.encode(owner.request.harness.id, origin))
}
