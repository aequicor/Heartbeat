package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutorAddress
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutors
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeAttempt
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeEnd
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeStage
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicRelease
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.ReleaseOutcome
import io.aequicor.heartbeat.feature.organicai.impl.domain.cellExecution
import io.aequicor.heartbeat.feature.organicai.impl.domain.judgeExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Fences canonical retirement before draining, then proves termination of exact requests without resuming them. */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class OrganicExecutorLifecycle(
    private val journal: OrganismJournal,
    private val native: OrganicNativeJournal,
    private val control: SessionExecutionControl,
    private val facade: EngineFacade,
    private val cells: CellSessions,
) : OrganicExecutors {
    private val log = Log.tag("OrganicExecutorLifecycle")
    private val reconciliation = Mutex()

    override suspend fun load(): List<Organism> = journal.load()

    override suspend fun persist(organism: Organism) {
        val previous = if (journal.read(organism.id) == null) emptyList() else attempts(organism)
        val retired = retired(organism, previous)
        if (retired.isEmpty()) journal.save(organism)
        retired.forEach { binding -> control.fence(binding) { journal.save(organism) } }
    }

    override suspend fun reconcileJudges(organism: Organism) {
        bounded {
            reconciliation.withLock { cleanupJudges(organism, attempts(organism)) }
        }
    }

    override suspend fun hibernate(organism: Organism): OrganicRelease =
        releaseNative(organism, organism.cells.map { it.id }, isHibernating = true, isArchiving = false)

    override suspend fun release(organism: Organism, cells: List<CellId>, mode: ReleaseMode): OrganicRelease =
        releaseNative(organism, cells, isHibernating = false, isArchiving = mode == ReleaseMode.Lyse)

    private suspend fun releaseNative(
        organism: Organism,
        cells: List<CellId>,
        isHibernating: Boolean,
        isArchiving: Boolean,
    ): OrganicRelease {
        persist(organism)
        val stopped = mutableSetOf<CellId>()
        val references = mutableMapOf<CellId, Set<SessionRef>>()
        val isConfirmed = bounded {
            reconciliation.withLock {
                val targets = organism.cells.filter {
                    !organism.isDeveloping || it.id in cells || it.phase is CellPhase.Dead
                }
                val results = coroutineScope {
                    targets.map { cell ->
                        async {
                            cell.id to cleanupCell(
                                organism,
                                cell,
                                isHibernating,
                                isArchiving,
                            )
                        }
                    }.awaitAll()
                }
                stopped += results.filter { it.second }.map { it.first }
                val known = attempts(organism)
                stopped.forEach { id ->
                    references[id] = known.filter {
                        val address = OrganicExecutorAddress.parse(it.claim.address)
                        address.kind == OrganicExecutorAddress.Kind.Cell && address.member == id.value
                    }.mapNotNull { it.session }.toSet()
                }
                val hasJudgeProof = cleanupJudges(organism, attempts(organism), isHibernating)
                val isComplete = results.all { it.second } && hasJudgeProof
                if (isComplete && !organism.isDeveloping) {
                    val incarnation = organism.incarnation ?: return@withLock false
                    val references = attempts(organism).mapNotNull { it.session }.toSet()
                    native.released(organism.id, incarnation, organism.version, references)
                }
                isComplete
            }
        }
        return OrganicRelease(stopped, isConfirmed, references)
    }

    private suspend fun cleanupCell(
        organism: Organism,
        cell: Cell,
        isHibernating: Boolean,
        isArchiving: Boolean,
    ): Boolean = guarded {
        val key = CellKey(organism.id, cell.id)
        val isFinal = cell.phase is CellPhase.Dead
        val local = cells.prepareRelease(key, cell.turns, isFinal)
        val claims = attempts(organism).filter {
            val address = OrganicExecutorAddress.parse(it.claim.address)
            address.kind == OrganicExecutorAddress.Kind.Cell && address.member == cell.id.value
        }
        val bindings = claims.mapNotNull { it.claim.execution }.toSet() +
            listOfNotNull(organism.incarnation?.let { organism.cellExecution(cell.id) })
        if (isFinal) bindings.forEach { control.awaitAdmissions(it) }
        val clean: suspend () -> Boolean = {
            val current = attempts(organism).filter {
                val address = OrganicExecutorAddress.parse(it.claim.address)
                address.kind == OrganicExecutorAddress.Kind.Cell && address.member == cell.id.value
            }
            val refs = local + listOfNotNull(cell.session) + current.mapNotNull { it.session } +
                bindings.flatMap { control.references(it) }
            val isClosed = cleanup(organism, current, refs.toSet(), key) &&
                cells.closeReleased(key, cell.turns) == ReleaseOutcome.Stopped
            if (isClosed && isArchiving) {
                current.asSequence().filter { it.claim.execution != null }.mapNotNull { it.session }.distinct()
                    .forEach { facade.archiveOrganic(it) }
            }
            isClosed
        }
        if (isHibernating && !isFinal) suspended(bindings.toList(), clean) else clean()
    }

    private suspend fun cleanupJudges(
        organism: Organism,
        attempts: List<OrganicNativeAttempt>,
        includeOpen: Boolean = false,
    ): Boolean {
        val open = organism.cases.map { it.id.value }.toSet()
        val claims = attempts.filter {
            val address = OrganicExecutorAddress.parse(it.claim.address)
            address.kind == OrganicExecutorAddress.Kind.Judge && (includeOpen || address.member !in open)
        }
        val canonical = if (organism.incarnation == null) {
            emptyList()
        } else {
            val cases = organism.cases + organism.trials.map { it.case }
            cases.filter { includeOpen || it.id.value !in open }.map { organism.judgeExecution(it.id) }
        }
        val bindings = claims.mapNotNull { it.claim.execution }.toSet() + canonical
        val clean: suspend () -> Boolean = {
            val current = this.attempts(organism).filter {
                val address = OrganicExecutorAddress.parse(it.claim.address)
                address.kind == OrganicExecutorAddress.Kind.Judge && (includeOpen || address.member !in open)
            }
            val refs = current.mapNotNull { it.session } +
                organism.trials.filter { includeOpen || it.case.id.value !in open }.mapNotNull { it.judge } +
                bindings.flatMap { control.references(it) }
            cleanup(organism, current, refs.toSet())
        }
        return if (includeOpen) {
            suspended(bindings.toList(), clean)
        } else {
            bindings.forEach { control.awaitAdmissions(it) }
            clean()
        }
    }

    private suspend fun suspended(bindings: List<SessionExecutionBinding>, block: suspend () -> Boolean): Boolean =
        if (bindings.isEmpty()) {
            block()
        } else {
            control.suspended(bindings.first()) { suspended(bindings.drop(1), block) }
        }

    private suspend fun cleanup(
        organism: Organism,
        claims: List<OrganicNativeAttempt>,
        references: Set<SessionRef>,
        key: CellKey? = null,
    ): Boolean {
        val outcomes = coroutineScope { claims.map { async { guarded { settle(organism, it, key) } } }.awaitAll() }
        val tokens = claims.map { it.claim.token }.toSet()
        val settled = attempts(organism).filter { it.claim.token in tokens && it.isQuiescent }
        val proven = settled.mapNotNull { it.session }.toSet()
        return outcomes.all { it } && references.all { it in proven }
    }

    private suspend fun settle(organism: Organism, attempt: OrganicNativeAttempt, key: CellKey?): Boolean {
        val next = when (attempt.stage) {
            OrganicNativeStage.Reserved -> attempt.copy(
                stage = OrganicNativeStage.Settled,
                end = OrganicNativeEnd.NotEntered,
            )

            OrganicNativeStage.Opened -> attempt.copy(
                stage = OrganicNativeStage.Settled,
                end = OrganicNativeEnd.NotSubmitted,
            )

            OrganicNativeStage.Opening, OrganicNativeStage.Entered,
            OrganicNativeStage.Sending, OrganicNativeStage.Accepted,
            -> stop(attempt, key)

            OrganicNativeStage.Settled -> null
        } ?: return attempt.isQuiescent
        native.advance(
            organism.id,
            checkNotNull(organism.incarnation),
            attempt,
            next.copy(stage = OrganicNativeStage.Settled, revision = attempt.revision + 1),
        )
        return true
    }

    private suspend fun stop(attempt: OrganicNativeAttempt, key: CellKey?): OrganicNativeAttempt? {
        val ref = attempt.session ?: return null
        val access = facade.engines.features(ref.engine).resolve(StopsOwnedTurns) as? FeatureAccess.Available
        val claim = attempt.claim
        val cold = access?.feature?.stop(ref, claim.request, OwnedTurnAccess(claim.target, claim.workspace))
        val result = (cold as? OwnedTurnStop.Confirmed)
            ?: (key?.let { cells.stopOwned(it, ref, claim.request) } as? OwnedTurnStop.Confirmed)
            ?: return null
        val turn = result.turn
        check(turn.request == claim.request && turn.target == claim.target) { "Native Stop returned another request" }
        return attempt.copy(
            stage = OrganicNativeStage.Settled,
            turn = attempt.turn ?: turn.id,
            outcome = turn.outcome,
            end = OrganicNativeEnd.ConfirmedStop,
        )
    }

    private suspend fun attempts(organism: Organism): List<OrganicNativeAttempt> =
        organism.incarnation?.let { native.attempts(organism.id, it) }.orEmpty()

    private fun retired(organism: Organism, attempts: List<OrganicNativeAttempt>): Set<SessionExecutionBinding> {
        if (organism.incarnation == null) return emptySet()
        val dead = organism.cells.filter { it.phase is CellPhase.Dead }.map { it.id }.toSet()
        val open = organism.cases.map { it.id.value }.toSet()
        val recorded = attempts.filter {
            val address = OrganicExecutorAddress.parse(it.claim.address)
            when (address.kind) {
                OrganicExecutorAddress.Kind.Cell -> CellId(address.member) in dead
                OrganicExecutorAddress.Kind.Judge -> address.member !in open
            }
        }.mapNotNull { it.claim.execution }
        val judges = organism.trials.filter { it.case.id.value !in open }.map { organism.judgeExecution(it.case.id) }
        return (recorded + dead.mapNotNull { organism.cellExecution(it) } + judges).toSet()
    }

    private suspend fun bounded(block: suspend () -> Boolean): Boolean = guarded {
        withTimeoutOrNull(CLEANUP_WAIT) { block() } ?: false.also {
            log.w { "organic native cleanup timed out; release stays unconfirmed" }
        }
    }

    private suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "organic native cleanup lacks complete evidence" }
        false
    }

    private companion object {
        val CLEANUP_WAIT = 10.seconds
    }
}
