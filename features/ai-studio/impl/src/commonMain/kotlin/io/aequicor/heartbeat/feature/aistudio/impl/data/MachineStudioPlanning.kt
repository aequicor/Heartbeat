package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineKey
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.api.isValidPlanChat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** [StudioPlanning] over the profile's planning machine, addressed only with its public intents. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineStudioPlanning(
    private val view: Lazy<PlanningView>,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) : StudioPlanning {
    private val log = Log.tag("StudioPlanning")

    override val availability: Flow<Boolean> = toggles.observe(PlanningModeEnabled)

    override val plans: Flow<Map<String, Plan>>
        get() = view.value.state
            .map { state -> (state as? PlanningState.Ready)?.plans.orEmpty().mapKeys { it.key.value } }
            .distinctUntilChanged()

    override suspend fun enter(chatId: String): Boolean {
        val ready = ready() ?: return false
        val chat = chatId.takeIf(::isValidPlanChat)?.let(::PlanChat) ?: return false
        if (ready.plans[chat]?.mode == PlanMode.Planning) return true
        val result = machines.send(PlanningMachineKey, PlanningIntent.Public.Enter(chat, clock.now()))
        log.i { "User started planning: $result" }
        return result == SendResult.Accepted
    }

    override suspend fun leave(chatId: String): Boolean {
        val chat = chatId.takeIf(::isValidPlanChat)?.let(::PlanChat) ?: return false
        val result = machines.send(PlanningMachineKey, PlanningIntent.Public.Leave(chat))
        log.i { "User left planning: $result" }
        return result == SendResult.Accepted
    }

    override suspend fun bind(chatId: String, session: SessionRef) {
        val chat = chatId.takeIf(::isValidPlanChat)?.let(::PlanChat) ?: return
        val ready = ready() ?: return
        if (chat !in ready.plans || ready.bindings[session] == chat) return
        val result = machines.send(PlanningMachineKey, PlanningIntent.Public.Bind(chat, session))
        log.d { "Plan bound to the chat's session: $result" }
    }

    /** Loaded plans, waiting briefly while they load; null while the feature is off or its plans are unreadable. */
    private suspend fun ready(): PlanningState.Ready? {
        if (!toggles.get(PlanningModeEnabled)) return null
        val state = withTimeoutOrNull(LOAD_TIMEOUT) {
            view.value.state.first { it is PlanningState.Ready || it is PlanningState.Failed }
        }
        if (state !is PlanningState.Ready) log.w { "Plans are not available: ${state ?: "loading"}" }
        return state as? PlanningState.Ready
    }

    private companion object {
        val LOAD_TIMEOUT = 5.seconds
    }
}
