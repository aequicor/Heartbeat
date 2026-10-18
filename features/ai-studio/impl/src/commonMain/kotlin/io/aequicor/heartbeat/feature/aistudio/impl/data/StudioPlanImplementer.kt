package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementation
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import kotlinx.coroutines.flow.first

/** The parts of the studio repository that implementing a plan uses. */
internal interface StudioPlanHost {
    /** The chat with its current confirmed configuration. */
    suspend fun planChat(id: String): StudioChatRecord

    /** Fails when [target] is not an enabled route the chat can use. */
    suspend fun validatePlanTarget(id: String, target: EngineTarget)

    /** Changes one setting of the chat's current native session; false when it was not applied. */
    suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean

    /** Continues the chat in a new native session as a reserved run; the old session is closed. */
    suspend fun rolloverForPlan(id: String, settings: RunSettings): RunOutcome

    /** Makes [settings] the chat's configuration, so its next native session opens with them. */
    suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings)

    /** Runs [prompt] as the user's turn of the chat. */
    suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome
}

/**
 * Implements the plan of a planning chat in the same chat. The chosen route of the same engine and connection is
 * applied to the current session; another engine or connection, or a fresh context, continues the chat in a new
 * native session that opens on the chosen route, with the essence of the planning conversation. The plan leaves
 * planning only after the chat is ready for it, and stays attached to the context of the implementation turns.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioPlanImplementer(private val planning: StudioPlanning, private val host: StudioPlanHost) :
    StudioPlanRuntime {
    private val log = Log.tag("StudioPlanImplementer")

    override suspend fun implementPlan(
        sessionId: String,
        implementation: PlanImplementation,
        settings: RunSettings,
    ): RunOutcome = implement(sessionId, implementation, settings)

    private suspend fun implement(id: String, implementation: PlanImplementation, defaults: RunSettings): RunOutcome {
        val plan = planning.plans.first()[id]
        val chat = host.planChat(id)
        val target = studioModelTarget(implementation.settings.modelId)
        val isImplementable = plan?.mode == PlanMode.Planning && plan.revision == implementation.revision &&
            chat.organismId == null && chat.helper == null && target != null
        if (!isImplementable || target == null) {
            log.w { "Plan cannot be implemented: planning=${plan?.mode ?: "none"} target=${target != null}" }
            return RunOutcome.Failed
        }
        host.validatePlanTarget(id, target)
        val settings = defaults.copy(
            modelId = implementation.settings.modelId,
            approval = implementation.settings.approval,
        )
        val isPrepared = prepare(chat, target, implementation, settings)
        log.i { "Implement the plan: prepared=$isPrepared route changed=${chat.target?.isSameRoute(target) == false}" }
        if (!isPrepared || !planning.startImplementation(id, implementation.revision)) return RunOutcome.Failed
        return host.runPlanTurn(id, implementation.prompt.ifBlank { DEFAULT_PROMPT }, settings)
    }

    private suspend fun prepare(
        chat: StudioChatRecord,
        target: EngineTarget,
        implementation: PlanImplementation,
        settings: RunSettings,
    ): Boolean {
        val current = chat.target
        val isNewContext = (current != null && !current.isSameRoute(target)) || implementation.isFreshContext
        return when {
            chat.ref == null -> true.also { host.adoptPlanSettings(chat.id, implementation.settings) }

            isNewContext -> (host.rolloverForPlan(chat.id, settings) == RunOutcome.Completed).also { isCarried ->
                if (isCarried) host.adoptPlanSettings(chat.id, implementation.settings)
            }

            else -> changes(chat.configuration, implementation.settings).all { host.configure(chat.id, it) }
        }
    }

    private fun changes(current: StudioSessionSettings?, chosen: StudioSessionSettings): List<StudioSettingChange> =
        listOfNotNull(
            StudioSettingChange.Model(chosen.modelId).takeIf { current?.modelId != chosen.modelId },
            StudioSettingChange.Effort(
                chosen.reasoningEffort,
            ).takeIf { current?.reasoningEffort != chosen.reasoningEffort },
            StudioSettingChange.Approval(chosen.approval).takeIf { current?.approval != chosen.approval },
        )

    private fun EngineTarget.isSameRoute(other: EngineTarget): Boolean =
        engine == other.engine && binding == other.binding

    private companion object {
        const val DEFAULT_PROMPT = "Implement the approved plan."
    }
}
