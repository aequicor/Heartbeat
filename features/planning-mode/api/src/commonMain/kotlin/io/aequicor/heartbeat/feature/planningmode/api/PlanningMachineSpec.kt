package io.aequicor.heartbeat.feature.planningmode.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent.Internal
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent.Public
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState.Ready

/**
 * Plans of the profile. The studio enters and leaves planning and binds sessions, the agent writes and proposes,
 * the user writes, approves and starts the implementation; the machine checks revisions and remembers.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready(plans, bindings) | Persist(repaired) if any |
 * | Loading | LoadFailed | | Failed | |
 * | Failed | Retry | | Loading | Load |
 * | Ready | Enter | not planning yet; room for a new plan or a detached one to evict | Ready(mode Planning) | Persist |
 * | Ready | Leave | plan attached | Ready(mode Detached) | Persist |
 * | Ready | Bind | plan exists, session not bound to it | Ready(session → chat, other sessions released) | Persist |
 * | Ready | Write | writable (below) | Ready(text, revision + 1; Draft while planning) | Persist(chat) |
 * | Ready | Propose | planning, revision current, Draft | Ready(Proposed) | Persist |
 * | Ready | Approve | planning, revision current, not Approved | Ready(Approved) | Persist |
 * | Ready | StartImplementation | planning, revision current | Ready(mode Implementing, Approved) | Persist |
 * | Ready | Forget | plan exists | Ready(plan and its bindings dropped) | Persist(chat deleted) |
 * | Ready | SaveFailed | | Ready | SaveFailed |
 * | Idle / Loading / Failed | any other Public | — | ignored | plans are not known |
 *
 * A write is accepted from the current revision when it changes the text within [PlanLimits.MAX_CHARS]; the agent
 * writes only an attached plan. Effect failures: Load → LoadFailed, Persist → SaveFailed. Only a change of the text
 * rewrites a plan file.
 */
public val PlanningMachineSpec: PlanningSpec = machineSpec(PlanningMachineKey, PlanningState.Idle) {
    state<PlanningState.Idle> {
        on<Public.Start> {
            goto<PlanningState.Loading> { PlanningState.Loading }
            effect { PlanningEffect.Load }
        }
    }
    state<PlanningState.Loading> {
        on<Internal.Loaded> {
            goto<Ready> { Ready(intent.plans, intent.bindings) }
            effect {
                Ready(
                    intent.plans,
                    intent.bindings,
                ).persisted(intent.repaired).takeIf { intent.repaired.isNotEmpty() }
            }
        }
        on<Internal.LoadFailed> { goto<PlanningState.Failed> { PlanningState.Failed } }
    }
    state<PlanningState.Failed> {
        on<Public.Retry> {
            goto<PlanningState.Loading> { PlanningState.Loading }
            effect { PlanningEffect.Load }
        }
    }
    state<Ready> {
        on<Public.Enter>(guard = { state.canEnter(intent.chat) }) {
            stay { state.entered(intent) }
            effect { state.entered(intent).let { it.persisted(state.plans.keys - it.plans.keys) } }
        }
        on<Public.Leave>(guard = { state.plans[intent.chat]?.let { it.mode != PlanMode.Detached } == true }) {
            stay { state.changed(intent.chat) { it.copy(mode = PlanMode.Detached) } }
            effect { state.changed(intent.chat) { it.copy(mode = PlanMode.Detached) }.persisted() }
        }
        on<Public.Bind>(guard = { intent.chat in state.plans && state.bindings[intent.session] != intent.chat }) {
            stay { state.bound(intent) }
            effect { state.bound(intent).persisted() }
        }
        on<Public.Write>(guard = { state.canWrite(intent) }) {
            stay { state.written(intent) }
            effect { state.written(intent).persisted(setOf(intent.chat)) }
        }
        on<Public.Propose>(guard = { state.isPlanning(intent.chat, intent.revision, PlanStatus.Draft) }) {
            stay { state.changed(intent.chat) { it.copy(status = PlanStatus.Proposed) } }
            effect { state.changed(intent.chat) { it.copy(status = PlanStatus.Proposed) }.persisted() }
        }
        on<Public.Approve>(
            guard = {
                state.isPlanning(intent.chat, intent.revision) &&
                    state.plans[intent.chat]?.status != PlanStatus.Approved
            },
        ) {
            stay { state.changed(intent.chat) { it.copy(status = PlanStatus.Approved) } }
            effect { state.changed(intent.chat) { it.copy(status = PlanStatus.Approved) }.persisted() }
        }
        on<Public.StartImplementation>(guard = { state.isPlanning(intent.chat, intent.revision) }) {
            stay { state.implementing(intent.chat) }
            effect { state.implementing(intent.chat).persisted() }
        }
        on<Public.Forget>(guard = { intent.chat in state.plans }) {
            stay { state.without(intent.chat) }
            effect { state.without(intent.chat).persisted(setOf(intent.chat)) }
        }
        on<Internal.SaveFailed> {
            stay { state }
            output { PlanningOutput.SaveFailed }
        }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            PlanningEffect.Load -> Internal.LoadFailed
            is PlanningEffect.Persist -> Internal.SaveFailed
        }
    }
}

/** Typed spec of [PlanningMachineSpec]. */
public typealias PlanningSpec = MachineSpec<PlanningState, PlanningIntent, PlanningEffect, PlanningOutput>

/** Whether [chat] may enter planning: it is not planning yet and its plan exists or fits. */
private fun Ready.canEnter(chat: PlanChat): Boolean {
    val plan = plans[chat] ?: return plans.size < PlanLimits.MAX_PLANS || evictable() != null
    return plan.mode != PlanMode.Planning
}

/** The oldest detached plan, dropped to make room for a new one. */
private fun Ready.evictable(): PlanChat? =
    plans.values.filter { it.mode == PlanMode.Detached }.minByOrNull { it.updatedAt }?.chat

private fun Ready.entered(intent: Public.Enter): Ready {
    val existing = plans[intent.chat]
    if (existing != null) return changed(intent.chat) { it.copy(mode = PlanMode.Planning) }
    val room = if (plans.size < PlanLimits.MAX_PLANS) this else evictable()?.let(::without) ?: this
    val plan = Plan(intent.chat, PlanMode.Planning, updatedAt = intent.at)
    return room.copy(plans = room.plans + (intent.chat to plan), revision = room.revision + 1)
}

private fun Ready.canWrite(intent: Public.Write): Boolean {
    val plan = plans[intent.chat] ?: return false
    val text = applyEdit(plan.text, intent.edit)
    val isAllowed = plan.mode != PlanMode.Detached || intent.author != PlanAuthor.Agent
    return isAllowed && intent.base == plan.revision && text != plan.text && text.length <= PlanLimits.MAX_CHARS
}

private fun Ready.written(intent: Public.Write): Ready = changed(intent.chat) { plan ->
    plan.copy(
        text = applyEdit(plan.text, intent.edit),
        revision = plan.revision + 1,
        status = if (plan.mode == PlanMode.Planning) PlanStatus.Draft else plan.status,
        updatedAt = intent.at,
        updatedBy = intent.author,
    )
}

private fun Ready.isPlanning(chat: PlanChat, revision: Long, status: PlanStatus? = null): Boolean {
    val plan = plans[chat] ?: return false
    return plan.mode == PlanMode.Planning && plan.revision == revision && (status == null || plan.status == status)
}

private fun Ready.implementing(chat: PlanChat): Ready =
    changed(chat) { it.copy(mode = PlanMode.Implementing, status = PlanStatus.Approved) }

private fun Ready.bound(intent: Public.Bind): Ready = copy(
    bindings = bindings.filterValues { it != intent.chat } + (intent.session to intent.chat),
    revision = revision + 1,
)

private fun Ready.without(chat: PlanChat): Ready = copy(
    plans = plans - chat,
    bindings = bindings.filterValues { it != chat },
    revision = revision + 1,
)

private inline fun Ready.changed(chat: PlanChat, change: (Plan) -> Plan): Ready {
    val plan = plans[chat] ?: return this
    return copy(plans = plans + (chat to change(plan)), revision = revision + 1)
}

/** The save of this state; called on the state after the transition, so its revision is current. */
private fun Ready.persisted(changed: Set<PlanChat> = emptySet()): PlanningEffect.Persist =
    PlanningEffect.Persist(plans, bindings, changed, revision)
