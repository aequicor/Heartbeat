package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.TransitionBuilder
import io.aequicor.heartbeat.core.statemachine.TransitionScope
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure

/**
 * Organic AI: sessions organized as organisms. A zygote grows from the goal; working cells divide, complain about
 * cancerous cells and ask for binding answers through their hosted tools; each case goes to a fresh immune session.
 * Results stay in durable inboxes and are read by a hosted tool. Only an explicitly requested wait wakes a resting
 * cell, with a host-only reminder to read its inbox. A dispute can wake all its waiting parties. Every change of an
 * organism inside [OrganicAiState.Living] is a data update, so turns and judgements of other cells keep running.
 *
 * | From | Intent | Guard | To / update | Effects | Output |
 * |---|---|---|---|---|---|
 * | Dormant, Broken | Awaken | — | Awakening | Restore | — |
 * | Awakening | Restored | — | Living(awakened) | Revive(developing), if any | — |
 * | Awakening | RestoreFailed | — | Broken, journal untouched | — | — |
 * | Awakening, Broken | Sleep | — | Dormant | — | — |
 * | Living | Sleep | — | Hibernating | Hibernate(all) | — |
 * | Hibernating | Hibernated | — | Dormant | — | — |
 * | Living | Conceive | new id | + organism, zygote Working(Genesis) | Persist; Drive, or Resolve without a model | — |
 * | Living | Targeted / Unresolved | developing, no model yet | model / zygote Stalled(NoModel) | Persist; Drive | — |
 * | Living | Resume | stalled / unarmed resting | recovery / reminder | Persist; DriveCells / Drive / Resolve | — |
 * | Living | Abort | developing | living cells Dead(Aborted), Aborted | Persist; Release(Lyse) | Finished |
 * | Living | Divide | parent working, limits, next cell id | + child Working(Genesis) | Persist; Drive(child) | — |
 * | Living | Complain / Dispute | filer working, no refusal, next case id | + case, + trial | Persist; Judge | — |
 * | Living | JudgeConvened | trial without ruling | trial judge session | Persist | — |
 * | Living | SessionBound | turn of request, no session yet | session | Persist | — |
 * | Living | TurnAccepted / PermissionsChanged | turn of request | observed turn / awaiting | — | — |
 * | Living | ReceiveLetters | current request, valid cursor | advance read cursor, disarm wait | Persist | — |
 * | Living | AwaitResults | current request, unread or [Organism.isWaiting] | arm wait | Persist | — |
 * | Living | Decide | an awaited request accepts the decision | — | Respond | — |
 * | Living | TurnSettled(Answered) | turn of request | see below | Persist; Drive?; Release(Retire)? | Finished? |
 * | Living | TurnSettled(Broke) | turn of request | see below | Persist; Drive?; Release(Lyse)? | — |
 * | Living | Ruled | open case, developing | see below | Persist; Drive?; Drive(plaintiff)?; Release(Lyse)? | — |
 *
 * An answer starts a reminder turn only when the cell explicitly requested a wait and has unread results. A cell
 * with living children, a filed case, a dispute it participates in or unread results rests; otherwise it completes
 * (the zygote completes the organism). A broken zygote stalls; any other broken cell dies with its descendants and
 * its parent is told. A kill lyses the accused subtree and may wake its waiting parent and plaintiff.
 * All result text is returned through the receive tool,
 * never through a prompt. A result arriving during a turn never interrupts it. Explicit waits and read cursors
 * survive restart; legacy saved letters turns are moved back into inboxes before recovery.
 *
 * Anything else is ignored: stale feedback of an ended cell or a replaced request, unknown organisms, refused tool
 * requests and every organism intent outside Living. Effect failures map to: Restore → RestoreFailed, Resolve →
 * Unresolved, Drive → TurnSettled(Broke(Engine)), Judge → Ruled(None), Hibernate → Hibernated.
 */
public val OrganicAiMachineSpec: MachineSpec<OrganicAiState, OrganicAiIntent, OrganicAiEffect, OrganicAiOutput> =
    machineSpec(OrganicAiMachineKey, OrganicAiState.Dormant) {
        state<OrganicAiState.Dormant> {
            on<OrganicAiIntent.Public.Awaken> { awaken() }
        }
        state<OrganicAiState.Awakening> {
            on<OrganicAiIntent.Internal.Restored> {
                goto<OrganicAiState.Living> {
                    OrganicAiState.Living(intent.organisms.map(Organism::awakened).associateBy(Organism::id))
                }
                effect {
                    intent.organisms.map(Organism::awakened)
                        .filter { it.isDeveloping }
                        .takeIf { it.isNotEmpty() }
                        ?.let(OrganicAiEffect::Revive)
                }
            }
            on<OrganicAiIntent.Internal.RestoreFailed> { goto<OrganicAiState.Broken> { OrganicAiState.Broken } }
            on<OrganicAiIntent.Public.Sleep> { goto<OrganicAiState.Dormant> { OrganicAiState.Dormant } }
        }
        state<OrganicAiState.Living> {
            on<OrganismIntent>(guard = { state.evolve(intent) != null }) {
                stay { state.evolve(intent)?.let(state::apply) ?: state }
                effect { step()?.takeIf { it.isDurable }?.let { OrganicAiEffect.Persist(it.organism) } }
                effect { step()?.takeIf { it.isResolving }?.let { OrganicAiEffect.Resolve(it.organism.id) } }
                effect { step()?.let { it.driveEffect(it.drive) } }
                effect { step()?.let { it.driveEffect(it.rouse) } }
                effect {
                    step()?.takeIf { it.driveCells.isNotEmpty() }
                        ?.let { OrganicAiEffect.DriveCells(it.organism, it.driveCells) }
                }
                effect { step()?.let { step -> step.judge?.let { OrganicAiEffect.Judge(step.organism, it) } } }
                effect {
                    step()?.takeIf { it.release.isNotEmpty() }
                        ?.let { OrganicAiEffect.Release(it.organism, it.release, it.releaseMode) }
                }
                effect {
                    step()?.let { step ->
                        step.response?.let { OrganicAiEffect.Respond(step.organism.id, it.cell, it.decision) }
                    }
                }
                output {
                    step()?.takeIf { it.isFinished }
                        ?.let { OrganicAiOutput.Finished(it.organism.id, it.organism.status) }
                }
            }
            on<OrganicAiIntent.Public.Sleep> {
                goto<OrganicAiState.Hibernating> { OrganicAiState.Hibernating }
                // Every organism is saved again: an ending whose own write was cut off must not wake up developing.
                effect { OrganicAiEffect.Hibernate(state.organisms.values.toList()) }
            }
        }
        state<OrganicAiState.Hibernating> {
            on<OrganicAiIntent.Internal.Hibernated> { goto<OrganicAiState.Dormant> { OrganicAiState.Dormant } }
        }
        state<OrganicAiState.Broken> {
            on<OrganicAiIntent.Public.Awaken> { awaken() }
            on<OrganicAiIntent.Public.Sleep> { goto<OrganicAiState.Dormant> { OrganicAiState.Dormant } }
        }
        onEffectFailure { effect, error -> effect.failure(error) }
    }

private typealias OrganicTransition<T, J> =
    TransitionBuilder<OrganicAiState, T, OrganicAiIntent, J, OrganicAiEffect, OrganicAiOutput>

private fun <T : OrganicAiState> OrganicTransition<T, OrganicAiIntent.Public.Awaken>.awaken() {
    goto<OrganicAiState.Awakening> { OrganicAiState.Awakening }
    effect { OrganicAiEffect.Restore }
}

/** The step of the current organism intent; recomputed per lambda, the rules are pure. */
private fun TransitionScope<OrganicAiState.Living, OrganismIntent>.step(): Step? = state.evolve(intent)

/** The turn the step started for [cell], as a drive of that cell's request. */
private fun Step.driveEffect(cell: CellId?): OrganicAiEffect.Drive? {
    cell ?: return null
    val phase = organism.cell(cell)?.phase as? CellPhase.Working ?: return null
    return OrganicAiEffect.Drive(organism, cell, phase.request)
}

private fun OrganicAiEffect.failure(error: Throwable): OrganicAiIntent? = when (this) {
    OrganicAiEffect.Restore -> OrganicAiIntent.Internal.RestoreFailed

    is OrganicAiEffect.Resolve -> OrganicAiIntent.Internal.Unresolved(organism)

    is OrganicAiEffect.Drive -> OrganicAiIntent.Internal.TurnSettled(
        organism.id,
        cell,
        request,
        Settlement.Broke(Breakdown.Engine((error as? EngineException)?.failure ?: EngineFailure.Unknown())),
    )

    is OrganicAiEffect.Judge -> OrganicAiIntent.Internal.Ruled(
        organism.id,
        case,
        Ruling.None("The immune system could not reach a decision."),
    )

    is OrganicAiEffect.Hibernate -> OrganicAiIntent.Internal.Hibernated

    is OrganicAiEffect.Revive, is OrganicAiEffect.DriveCells,
    is OrganicAiEffect.Persist, is OrganicAiEffect.Respond, is OrganicAiEffect.Release,
    -> null
}
