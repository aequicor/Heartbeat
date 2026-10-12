package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent.Internal
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent.Public
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState.Ready

/**
 * Settings and compactions of the profile. Hosts run compactions: they claim a pending one when its session is idle
 * and report the result; the machine only decides and remembers.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready(stored or default settings, stored ledgers) | |
 * | Loading | LoadFailed | | Ready() | |
 * | Ready | UpdateSettings | settings differ | Ready(settings, revision + 1) | Save(settings, revision + 1) |
 * | Ready | Compact | session not running | Ready(pending of the session replaced) | |
 * | Ready | TurnSettled | | Ready(usage; if automatic and the session is idle: decideCompaction) | |
 * | Ready | UpdateLedger | | Ready(ledger updated, ledgerRevision + 1) | SaveLedger(session) |
 * | Ready | Claim | session pending, not running | Ready(pending → running) | |
 * | Ready | Finished | session running | Ready(running removed) | |
 * | Ready | RolledOver | old ≠ new | Ready(pending, mark, ledger of old → new) | SaveLedger: new, then old deleted, if moved |
 * | Ready | SessionClosed | | Ready(session dropped) | SaveLedger(session, deleted) if it had one |
 * | Ready | SaveFailed | | Ready | SaveFailed |
 * | Idle / Loading | any other Public | — | ignored | settings are not known before loading |
 *
 * Effect failures: Load → LoadFailed, Save and SaveLedger → SaveFailed. Every ledger effect writes one session.
 */
public val ContextOptimizationMachineSpec: ContextOptimizationSpec =
    machineSpec(ContextOptimizationMachineKey, ContextOptimizationState.Idle) {
        state<ContextOptimizationState.Idle> {
            on<Public.Start> {
                goto<ContextOptimizationState.Loading> { ContextOptimizationState.Loading }
                effect { ContextOptimizationEffect.Load }
            }
        }
        state<ContextOptimizationState.Loading> {
            on<Internal.Loaded> {
                goto<Ready> { Ready(intent.settings ?: ContextOptimizationSettings(), ledgers = intent.ledgers) }
            }
            on<Internal.LoadFailed> { goto<Ready> { Ready() } }
        }
        state<Ready> {
            on<Public.UpdateSettings>(guard = { intent.settings != state.settings }) {
                stay { state.copy(settings = intent.settings, revision = state.revision + 1) }
                effect { ContextOptimizationEffect.Save(intent.settings, state.revision + 1) }
            }
            on<Public.Compact>(guard = { intent.session !in state.running }) {
                stay {
                    val method = intent.method ?: state.settings.defaultMethod()
                    val request = PendingCompaction(method, intent.origin, intent.instructions)
                    state.copy(pending = state.pending + (intent.session to request))
                }
            }
            on<Public.TurnSettled> { stay { state.settled(intent) } }
            on<Public.UpdateLedger> {
                stay { state.ledgerUpdated(intent) }
                effect { state.ledgerUpdated(intent).ledgerSaved(intent.session) }
            }
            on<Public.Claim>(guard = { intent.session in state.pending && intent.session !in state.running }) {
                stay {
                    val claimed = state.pending.getValue(intent.session)
                    state.copy(
                        pending = state.pending - intent.session,
                        running = state.running + (intent.session to claimed),
                    )
                }
            }
            on<Public.Finished>(guard = { intent.session in state.running }) {
                stay { state.copy(running = state.running - intent.session) }
            }
            on<Public.RolledOver>(guard = { intent.old != intent.new }) {
                stay { state.rolledOver(intent.old, intent.new) }
                // The new record is written before the old one is deleted, so an interruption never loses the ledger.
                effect { state.movedLedger(intent.old, intent.new)?.ledgerSaved(intent.new) }
                effect { state.movedLedger(intent.old, intent.new)?.ledgerSaved(intent.old) }
            }
            on<Public.SessionClosed> {
                stay { state.without(intent.session) }
                effect {
                    state.without(intent.session).ledgerSaved(intent.session).takeIf { intent.session in state.ledgers }
                }
            }
            on<Internal.SaveFailed> {
                stay { state }
                output { ContextOptimizationOutput.SaveFailed }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                ContextOptimizationEffect.Load -> Internal.LoadFailed
                is ContextOptimizationEffect.Save, is ContextOptimizationEffect.SaveLedger -> Internal.SaveFailed
            }
        }
    }

/** Typed spec of [ContextOptimizationMachineSpec]. */
public typealias ContextOptimizationSpec = MachineSpec<
    ContextOptimizationState,
    ContextOptimizationIntent,
    ContextOptimizationEffect,
    ContextOptimizationOutput,
>

private fun Ready.settled(intent: Public.TurnSettled): Ready {
    val observed = copy(usage = intent.usage?.let { usage + (intent.session to it) } ?: (usage - intent.session))
    val isIdle = intent.session !in pending && intent.session !in running
    return if (intent.isAutomatic && isIdle) observed.decided(intent) else observed
}

private fun Ready.decided(intent: Public.TurnSettled): Ready {
    val decision = settings.decideCompaction(marks[intent.session] ?: CompactionMark(), intent.usage, intent.support)
    val isReset = decision.mark == CompactionMark()
    return copy(
        pending = decision.pending?.let { pending + (intent.session to it) } ?: pending,
        marks = if (isReset) marks - intent.session else marks + (intent.session to decision.mark),
    )
}

private fun Ready.rolledOver(old: SessionRef, new: SessionRef): Ready {
    val moved = without(old)
    return moved.copy(
        pending = pending[old]?.let { moved.pending + (new to it) } ?: moved.pending,
        marks = marks[old]?.let { moved.marks + (new to it) } ?: moved.marks,
        ledgers = ledgers[old]?.let { moved.ledgers + (new to it) } ?: moved.ledgers,
    )
}

/** The state after a rollover that moved the ledger of [old] to [new], or null when [old] had no ledger. */
private fun Ready.movedLedger(old: SessionRef, new: SessionRef): Ready? =
    if (old in ledgers) rolledOver(old, new) else null

private fun Ready.without(session: SessionRef): Ready = copy(
    pending = pending - session,
    running = running - session,
    marks = marks - session,
    usage = usage - session,
    ledgers = ledgers - session,
    ledgerRevision = if (session in ledgers) ledgerRevision + 1 else ledgerRevision,
)

private fun Ready.ledgerUpdated(intent: Public.UpdateLedger): Ready = copy(
    ledgers = ledgers + (intent.session to ledgers[intent.session].updated(intent.update, intent.at)),
    ledgerRevision = ledgerRevision + 1,
)

/** The save of the ledger of [session]; called on the state after the transition, so its revision is current. */
private fun Ready.ledgerSaved(session: SessionRef): ContextOptimizationEffect.SaveLedger =
    ContextOptimizationEffect.SaveLedger(session, ledgers[session], ledgerRevision)
