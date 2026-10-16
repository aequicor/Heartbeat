package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentAvailability
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentRequest
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStart
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStatus
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt

/** Background agents of other features: scheduler helper actions retained for their owner. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileBackgroundAgents(
    // Lazy: the actions reach the engine runtime; reading availability must not build it.
    private val actions: Lazy<BackgroundActions>,
    private val helpers: Lazy<HelperAgents>,
    private val results: ActionResults,
    private val toggles: FeatureToggles,
) : BackgroundAgents {
    private val log = Log.tag("BackgroundAgents")

    override suspend fun availability(parent: SessionRef): BackgroundAgentAvailability = when {
        !toggles.get(SchedulerEnabled) || !toggles.get(SchedulerActions) -> BackgroundAgentAvailability.Disabled
        actions.value.isHelper(parent) -> BackgroundAgentAvailability.HelperParent
        !helpers.value.canHost(parent) -> BackgroundAgentAvailability.NoHost
        else -> BackgroundAgentAvailability.Available
    }

    override suspend fun start(request: BackgroundAgentRequest): BackgroundAgentStart {
        val refusal = when (availability(request.parent)) {
            BackgroundAgentAvailability.Disabled -> "background actions are turned off"
            BackgroundAgentAvailability.HelperParent -> "a helper agent cannot start helpers of its own"
            BackgroundAgentAvailability.NoHost -> "no chat host can supervise a helper of this session"
            BackgroundAgentAvailability.Available -> launch(request)
        }
        log.i { "background agent ${request.id} owner=${request.owner} refused=${refusal != null}" }
        return if (refusal == null) BackgroundAgentStart.Started(request.id) else BackgroundAgentStart.Refused(refusal)
    }

    /** Returns why the action did not start, or null. */
    private suspend fun launch(request: BackgroundAgentRequest): String? {
        val spawn = SpawnRequest(
            request.parent,
            request.workspace,
            request.target,
            request.title,
            WakePrompt(RequestId("action_${request.id.value}"), request.task, request.directive),
            model = request.model,
        )
        return actions.value.startAgent(
            request.id,
            spawn,
            request.initiator,
            retainedBy = request.owner,
            awaitAcceptance = false,
        )
    }

    override suspend fun status(id: ActionId, owner: String): BackgroundAgentStatus {
        val record = results.retained(owner).firstOrNull { it.id == id } ?: return BackgroundAgentStatus.Unknown
        val payload = record.payload ?: return BackgroundAgentStatus.Running
        return finished(payload)
    }

    override suspend fun retained(owner: String): List<ActionId> = results.retained(owner).map { it.id }

    override suspend fun acknowledge(id: ActionId, owner: String) = results.acknowledge(id, owner)
}

/** Reads the helper action payload: `status: finished` plus the final message, or another status line. */
internal fun finished(payload: String): BackgroundAgentStatus.Finished {
    val status = payload.lineSequence().first().removePrefix(STATUS_PREFIX).trim()
    val message = payload.substringAfter("\n$FINAL_MESSAGE\n", missingDelimiterValue = "")
    return if (status == FINISHED) {
        BackgroundAgentStatus.Finished(isCompleted = true, text = message)
    } else {
        val text = if (message.isEmpty()) status else "$status\n$message"
        BackgroundAgentStatus.Finished(isCompleted = false, text = text)
    }
}

private const val STATUS_PREFIX = "status:"
private const val FINISHED = "finished"
private const val FINAL_MESSAGE = "final message:"
