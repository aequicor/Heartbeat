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
 * | Loading | Loaded | | Ready(stored or default settings) | |
 * | Loading | LoadFailed | | Ready() | |
 * | Ready | UpdateSettings | settings differ | Ready(settings, revision + 1) | Save(settings, revision + 1) |
 * | Ready | Compact | session not running | Ready(pending of the session replaced) | |
 * | Ready | TurnSettled | session neither pending nor running | Ready(decideCompaction: mark, maybe pending) | |
 * | Ready | Claim | session pending, not running | Ready(pending → running) | |
 * | Ready | Finished | session running | Ready(running removed) | |
 * | Ready | RolledOver | old ≠ new | Ready(pending and mark of old moved to new, old dropped) | |
 * | Ready | SessionClosed | | Ready(session dropped) | |
 * | Ready | SaveFailed | | Ready | SaveFailed |
 * | Idle / Loading | any other Public | — | ignored | settings are not known before loading |
 *
 * Effect failures: Load → LoadFailed, Save → SaveFailed.
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
                goto<Ready> { Ready(intent.settings ?: ContextOptimizationSettings()) }
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
            on<Public.TurnSettled>(guard = { intent.session !in state.pending && intent.session !in state.running }) {
                stay { state.settled(intent) }
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
            }
            on<Public.SessionClosed> { stay { state.without(intent.session) } }
            on<Internal.SaveFailed> {
                stay { state }
                output { ContextOptimizationOutput.SaveFailed }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                ContextOptimizationEffect.Load -> Internal.LoadFailed
                is ContextOptimizationEffect.Save -> Internal.SaveFailed
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
    )
}

private fun Ready.without(session: SessionRef): Ready =
    copy(pending = pending - session, running = running - session, marks = marks - session)
