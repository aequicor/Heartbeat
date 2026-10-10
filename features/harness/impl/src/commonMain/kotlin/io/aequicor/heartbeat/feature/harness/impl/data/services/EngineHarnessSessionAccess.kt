package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Shared authority and read-only routing for session services and scheduling. */
internal interface HarnessSessionAccess {
    fun isCurrent(owner: HarnessInstanceTarget): Boolean
    suspend fun permit(owner: HarnessInstanceTarget, target: HarnessTarget?): HarnessDeliveryPermit?
    suspend fun session(session: SessionRef): HarnessSessionRoute
}

/** Resolves indexed metadata without refreshing, resuming or opening native sessions. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineHarnessSessionAccess(
    private val runtime: Lazy<HarnessRuntime>,
    private val access: Lazy<HarnessSchedulerAccess>,
    private val facade: Lazy<EngineFacade>,
) : HarnessSessionAccess {
    override fun isCurrent(owner: HarnessInstanceTarget): Boolean = owner.access.isActive &&
        runtime.value.publishedInstances.value.any { it === owner.access && it.request == owner.request }

    override suspend fun permit(owner: HarnessInstanceTarget, target: HarnessTarget?): HarnessDeliveryPermit? =
        withTimeoutOrNull(2.seconds) { access.value.permits(owner.request.harness.id, target).first() }

    override suspend fun session(session: SessionRef): HarnessSessionRoute {
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
internal data class HarnessSessionRoute(
    val handle: EngineSession,
    val captured: SessionSummary,
    val workspace: WorkspaceRef?,
) {
    fun isCurrent(): Boolean = handle.summary.value.let {
        it.ref == captured.ref && it.workspace == captured.workspace && it.lastRoute == captured.lastRoute
    }
    override fun toString(): String = "HarnessSessionRoute(***)"
}
