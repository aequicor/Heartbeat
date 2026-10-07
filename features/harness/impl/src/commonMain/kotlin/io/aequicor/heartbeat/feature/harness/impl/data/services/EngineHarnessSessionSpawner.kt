package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionSpawner
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnOwner
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnRecord
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnSubmission
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Captures routing and ancestry before native work; all later checks refer to that same publication and route. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineHarnessSessionSpawner(
    private val sessions: HarnessSessionAccess,
    private val operations: Lazy<HarnessSpawnOperations>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : HarnessSessionSpawner {
    private val ownership = HarnessOwnedContext()
    private val log = Log.tag("HarnessSpawns")

    override suspend fun spawn(
        owner: HarnessInstanceTarget,
        parent: SessionRef?,
        title: String,
        prompt: String,
        origin: HarnessCallOrigin,
    ): ScriptHelper {
        check(!origin.isHookRestricted) { "Hooks cannot spawn helpers" }
        check(sessions.isCurrent(owner)) { "Script activation is unavailable" }
        val context = ownership.encode(owner.request.harness.id, origin)
        val publisher = checkNotNull(sessions.permit(owner, null)) { "Harness publisher is unavailable" }
        val route = parent?.let { sessions.session(it) }
        val parentPermit = if (parent == null) {
            null
        } else {
            checkNotNull(sessions.permit(owner, HarnessTarget(parent, route?.workspace))) {
                "Parent is unavailable to this harness"
            }
        }
        val record = HarnessSpawnRecord(
            ActionId("hs_slot_" + Uuid.random().toHexString()),
            ActionId("hs_spawn_" + Uuid.random().toHexString()),
            owner.spawnOwner(),
            parent,
            RequestId(Uuid.random().toHexString()),
            RequestId(Uuid.random().toHexString()),
            origin,
        )
        val submission = HarnessSpawnSubmission(
            record,
            route?.workspace,
            title,
            HelperPrompt(
                record.request,
                prompt,
                handoff = HelperHandoff(ownerFeature = HARNESS_WAKE_OWNER, ownerContext = context),
            ),
        ) {
            publisher.isCurrent() && (parentPermit == null || parentPermit.isCurrent()) &&
                sessions.isCurrent(owner) && (route == null || route.isCurrent())
        }
        currentCoroutineContext().ensureActive()
        check(submission.isAdmitted()) { "Harness helper admission was revoked" }
        return operations.value.spawn(submission)
    }

    override fun retire(owner: HarnessInstanceTarget) {
        val identity = owner.spawnOwner()
        profile.coroutineScope.launch {
            while (isActive) {
                try {
                    if (operations.value.retire(identity)) return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(harnessScriptFailure(error)) { "Retry script helper retirement" }
                }
                delay(5.seconds)
            }
        }
    }
}

private fun HarnessInstanceTarget.spawnOwner(): HarnessSpawnOwner =
    HarnessSpawnOwner(request.harness.id, request.item.id, request.generation)
