package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.api.isSandboxed
import io.aequicor.heartbeat.feature.organicai.api.zygote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Executes organic AI effects; failures are mapped by the spec's `onEffectFailure`. */
internal class OrganicAiEffects(
    private val journal: OrganismJournal,
    private val targets: DefaultTargets,
    private val cells: CellSessions,
    private val driver: CellDriver,
    private val court: ImmunityCourt,
    private val workspaces: CellWorkspaces,
) : EffectHandler<OrganicAiEffect, OrganicAiIntent> {
    private val log = Log.tag("OrganicAiEffects")

    override suspend fun handle(effect: OrganicAiEffect, machine: EffectScope<OrganicAiIntent>) {
        when (effect) {
            OrganicAiEffect.Restore -> restore(machine)

            is OrganicAiEffect.Revive -> revive(effect.organisms, machine)

            is OrganicAiEffect.DriveCells -> runIsolated(
                effect.cells.mapNotNull { id ->
                    (effect.organism.cell(id)?.phase as? CellPhase.Working)
                        ?.let { OrganicAiEffect.Drive(effect.organism, id, it.request) }
                },
                machine,
            )

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
                val ruling = court.judge(effect.organism, effect.case) { session ->
                    machine.send(OrganicAiIntent.Internal.JudgeConvened(effect.organism.id, effect.case, session))
                }
                machine.send(OrganicAiIntent.Internal.Ruled(effect.organism.id, effect.case, ruling))
            }

            is OrganicAiEffect.Release -> release(effect, machine)
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
        val organisms = journal.load().map { organism ->
            if (organism.status == OrganismStatus.Aborted && cells.hasPendingRelease(organism.id)) {
                organism.copy(sessions = SessionRelease.Unconfirmed)
            } else {
                organism
            }
        }
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
        runIsolated(work, machine)
    }

    private suspend fun runIsolated(work: List<OrganicAiEffect>, machine: EffectScope<OrganicAiIntent>) {
        coroutineScope {
            work.forEach { effect ->
                launch { isolated(effect, machine) }.invokeOnCompletion { cause ->
                    // As the runtime does for its own effects: a cancellation from inside a revival that goes on
                    // (a timeout) is a failure, or the cell would keep no driver.
                    if (cause is CancellationException && isActive) launch { cancelledInside(effect, cause, machine) }
                }
            }
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

    private suspend fun cancelledInside(
        effect: OrganicAiEffect,
        cause: CancellationException,
        machine: EffectScope<OrganicAiIntent>,
    ) {
        log.e(cause) { "revived ${effect::class.simpleName.orEmpty()} failed: cancelled from inside" }
        OrganicAiMachineSpec.onEffectFailure(effect, cause)?.let { machine.send(it) }
    }

    private suspend fun hibernate(organisms: List<Organism>, machine: EffectScope<OrganicAiIntent>) {
        organisms.forEach { saveQuietly(it) }
        cells.releaseAll()
        log.i { "hibernated ${organisms.size} developing organisms" }
        machine.send(OrganicAiIntent.Internal.Hibernated)
    }

    /**
     * Lets the cells go, all at once so one stuck turn does not delay the others. The release an abort asked for is
     * reported to the machine, confirmed only when every cell was seen to stop.
     */
    private suspend fun release(effect: OrganicAiEffect.Release, machine: EffectScope<OrganicAiIntent>) {
        val releases = coroutineScope {
            effect.cells.map { id ->
                async {
                    val cell = effect.organism.cell(id) ?: return@async ReleaseOutcome.Stopped to null
                    val mode = effect.mode
                    if (mode == ReleaseMode.Retire) captureResult(effect.organism, id)
                    val key = CellKey(effect.organism.id, id)
                    val outcome = cells.release(key, cell.session, mode, cell.turns)
                    val ended = cell.session?.takeIf { outcome == ReleaseOutcome.Stopped }
                        ?.let { Ended(key, it, cell.turns) }
                    outcome to ended
                }
            }.awaitAll()
        }
        val unconfirmed = releases.count { it.first != ReleaseOutcome.Stopped }
        val isConfirmed = unconfirmed == 0
        val summary = "organism ${effect.organism.id.value}: ${effect.cells.size} cells released (${effect.mode})"
        if (isConfirmed) log.i { summary } else log.w { "$summary, $unconfirmed not confirmed" }
        if (effect.organism.sessions == SessionRelease.Releasing) {
            machine.send(OrganicAiIntent.Internal.SessionsReleased(effect.organism.id, isConfirmed))
        }
        releases.mapNotNull { it.second }.forEach {
            driver.disconnect(effect.organism, it.session) { cells.isReleased(it.key, it.generation) }
        }
    }

    /**
     * A completed sandbox cell's checkout is captured, so its work survives however its parent integrates it, and the
     * zygote's too: that is the organism's result. A capture that fails is logged and left to the reader's retry.
     */
    private suspend fun captureResult(organism: Organism, id: CellId) {
        if (!organism.isSandboxed) return
        try {
            val result = workspaces.result(organism, id)
            log.i {
                "organism ${organism.id.value} cell ${id.value}: result ${if (result == null) "not " else ""}captured"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "organism ${organism.id.value} cell ${id.value}: result was not captured" }
        }
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

/** A cell session that was let go, with the turn generation it was released as. */
private data class Ended(val key: CellKey, val session: SessionRef, val generation: Int)
