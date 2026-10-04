package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.api.zygote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Executes organic AI effects; failures are mapped by the spec's `onEffectFailure`. */
internal class OrganicAiEffects(
    private val journal: OrganismJournal,
    private val targets: DefaultTargets,
    private val cells: CellSessions,
    private val driver: CellDriver,
    private val court: ImmunityCourt,
) : EffectHandler<OrganicAiEffect, OrganicAiIntent> {
    private val log = Log.tag("OrganicAiEffects")

    override suspend fun handle(effect: OrganicAiEffect, machine: EffectScope<OrganicAiIntent>) {
        when (effect) {
            OrganicAiEffect.Restore -> restore(machine)

            is OrganicAiEffect.Revive -> revive(effect.organisms, machine)

            is OrganicAiEffect.Hibernate -> hibernate(effect.organisms, machine)

            // An ending has no later change to carry it: its write must outlive a sleep that leaves Living.
            is OrganicAiEffect.Persist -> withContext(NonCancellable) { journal.save(effect.organism) }

            is OrganicAiEffect.Resolve -> resolve(effect, machine)

            is OrganicAiEffect.Drive -> driver.drive(effect.organism, effect.cell, effect.request, machine)

            is OrganicAiEffect.Respond -> {
                log.i { "organism ${effect.organism.value} cell ${effect.cell.value}: user decision delivered" }
                cells.respond(CellKey(effect.organism, effect.cell), effect.decision)
            }

            is OrganicAiEffect.Judge -> {
                val ruling = court.judge(effect.organism, effect.case)
                machine.send(OrganicAiIntent.Internal.Ruled(effect.organism.id, effect.case, ruling))
            }

            is OrganicAiEffect.Release -> release(effect)
        }
    }

    private suspend fun resolve(effect: OrganicAiEffect.Resolve, machine: EffectScope<OrganicAiIntent>) {
        val target = targets.default()
        log.i { "organism ${effect.organism.value}: default model is ${if (target == null) "missing" else "found"}" }
        machine.send(
            target?.let { OrganicAiIntent.Internal.Targeted(effect.organism, it) }
                ?: OrganicAiIntent.Internal.Unresolved(effect.organism),
        )
    }

    private suspend fun restore(machine: EffectScope<OrganicAiIntent>) {
        val organisms = journal.load()
        log.i { "restored ${organisms.size} organisms, ${organisms.count { it.isDeveloping }} developing" }
        machine.send(OrganicAiIntent.Internal.Restored(organisms))
    }

    /**
     * Continues organisms after a restart. Their awakened snapshots (with new request ids) are saved before any turn
     * starts; then every working cell, missing model and open case is resumed, each failing on its own.
     */
    private suspend fun revive(organisms: List<Organism>, machine: EffectScope<OrganicAiIntent>) {
        organisms.forEach { saveQuietly(it) }
        val work = organisms.flatMap(::revival)
        log.i { "reviving ${organisms.size} organisms: ${work.size} turns, models and cases" }
        coroutineScope {
            work.forEach { effect -> launch { isolated(effect, machine) } }
        }
    }

    /** What a restored organism needs: its model, the turns of its working cells and its open cases. */
    private fun revival(organism: Organism): List<OrganicAiEffect> = buildList {
        if (organism.target == null) {
            if (organism.zygote.phase is CellPhase.Working) add(OrganicAiEffect.Resolve(organism.id))
        } else {
            organism.cells.forEach { cell ->
                (cell.phase as? CellPhase.Working)?.let { add(OrganicAiEffect.Drive(organism, cell.id, it.request)) }
            }
        }
        organism.cases.forEach { add(OrganicAiEffect.Judge(organism, it.id)) }
    }

    /** Runs [effect] like the runtime would: its failure becomes the intent the spec maps it to. */
    private suspend fun isolated(effect: OrganicAiEffect, machine: EffectScope<OrganicAiIntent>) {
        try {
            handle(effect, machine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "revived ${effect::class.simpleName.orEmpty()} failed" }
            OrganicAiMachineSpec.onEffectFailure(effect, e)?.let { machine.send(it) }
        }
    }

    private suspend fun hibernate(organisms: List<Organism>, machine: EffectScope<OrganicAiIntent>) {
        organisms.forEach { saveQuietly(it) }
        cells.releaseAll()
        log.i { "hibernated ${organisms.size} developing organisms" }
        machine.send(OrganicAiIntent.Internal.Hibernated)
    }

    private suspend fun release(effect: OrganicAiEffect.Release) {
        effect.cells.forEach { id ->
            cells.release(CellKey(effect.organism.id, id), effect.organism.cell(id)?.session, effect.mode)
        }
        log.i { "organism ${effect.organism.id.value}: ${effect.cells.size} cells released (${effect.mode})" }
    }

    /** A snapshot that cannot be written now is written by the next change of its organism or the next sleep. */
    private suspend fun saveQuietly(organism: Organism) {
        try {
            journal.save(organism)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "organism ${organism.id.value} version ${organism.version} was not saved" }
        }
    }
}
