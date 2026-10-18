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
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanEdit
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineKey
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.api.isValidPlanChat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * [StudioPlanning] over the profile's planning machine, addressed only with its public intents. Every command waits
 * briefly for loaded plans and does nothing while [PlanningModeEnabled] is off.
 */
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

    /** Whether unreadable plans were already reported to a binding turn; reset once they load again. */
    private val failureReported = MutableStateFlow(false)

    override val availability: Flow<Boolean> = toggles.observe(PlanningModeEnabled)

    override val plans: Flow<Map<String, Plan>>
        get() = view.value.state
            .map { state -> (state as? PlanningState.Ready)?.plans.orEmpty().mapKeys { it.key.value } }
            .distinctUntilChanged()

    override suspend fun canEnter(): Boolean {
        val ready = ready() ?: return false
        // The machine's guard for a chat without a plan: room for one, or a detached plan to evict.
        return ready.plans.size < PlanLimits.MAX_PLANS || ready.plans.values.any { it.mode == PlanMode.Detached }
    }

    override suspend fun enter(chatId: String): Boolean {
        val chat = chatOf(chatId) ?: return false
        val ready = ready() ?: return false
        if (ready.plans[chat]?.mode == PlanMode.Planning) return true
        return userCommand("started planning", PlanningIntent.Public.Enter(chat, clock.now()))
    }

    override suspend fun leave(chatId: String): Boolean {
        val chat = chatOf(chatId) ?: return false
        ready() ?: return false
        return userCommand("left planning", PlanningIntent.Public.Leave(chat))
    }

    override suspend fun bind(chatId: String, session: SessionRef) {
        val chat = chatOf(chatId) ?: return
        if (!toggles.get(PlanningModeEnabled)) return
        val state = withTimeoutOrNull(LOAD_TIMEOUT) {
            view.value.state.first { it is PlanningState.Ready || it is PlanningState.Failed }
        }
        when (state) {
            // Until plans load nobody knows whether this chat plans; its turn must not slip past read-only mode.
            null, PlanningState.Idle, PlanningState.Loading -> error("Plans are still loading; try again")

            is PlanningState.Ready -> {
                failureReported.value = false
                val plan = state.plans[chat]
                if (plan != null && state.bindings[session] != chat) bindPlan(plan, session)
            }

            // The read-only hook asks before changes of every session while plans are unreadable.
            PlanningState.Failed -> if (!failureReported.getAndUpdate { true }) log.w { "Plans are not available" }
        }
    }

    private suspend fun bindPlan(plan: Plan, session: SessionRef) {
        val result = machines.send(PlanningMachineKey, PlanningIntent.Public.Bind(plan.chat, session))
        if (result == SendResult.Accepted) {
            log.d { "Plan bound to the chat's session" }
        } else if ((view.value.state.value as? PlanningState.Ready)?.bindings?.get(session) == plan.chat) {
            // A concurrent turn of the same chat bound it between the snapshot and the send.
            log.d { "Plan was already bound to the chat's session: $result" }
        } else {
            // The read-only hook only knows bound sessions: an unbound planning session could change the workspace.
            check(plan.mode != PlanMode.Planning) { "The plan could not follow the chat into its session: $result" }
            log.w { "Plan of a ${plan.mode} chat stays unbound: $result" }
        }
    }

    override suspend fun startImplementation(chatId: String, revision: Long): Boolean {
        val chat = chatOf(chatId) ?: return false
        ready() ?: return false
        return userCommand("started implementing the plan", PlanningIntent.Public.StartImplementation(chat, revision))
    }

    override suspend fun approve(chatId: String, revision: Long): Boolean {
        val chat = chatOf(chatId) ?: return false
        ready() ?: return false
        return userCommand("approved the plan", PlanningIntent.Public.Approve(chat, revision))
    }

    override suspend fun write(chatId: String, text: String, base: Long): Boolean {
        val chat = chatOf(chatId) ?: return false
        ready() ?: return false
        val edit = PlanningIntent.Public.Write(chat, PlanEdit.Replace(text), base, PlanAuthor.User, clock.now())
        return userCommand("edited the plan", edit)
    }

    /** Sends a user's command; the registry itself warns about ignored intents and a stopped machine. */
    private suspend fun userCommand(action: String, intent: PlanningIntent.Public): Boolean {
        val result = machines.send(PlanningMachineKey, intent)
        // The single record of a command: a refusal (stale revision, changed plan) is routine, not a warning.
        if (result == SendResult.Accepted) log.i { "User $action" } else log.d { "Not $action: $result" }
        return result == SendResult.Accepted
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

    private fun chatOf(chatId: String): PlanChat? = chatId.takeIf(::isValidPlanChat)?.let(::PlanChat)

    private companion object {
        val LOAD_TIMEOUT = 5.seconds
    }
}
