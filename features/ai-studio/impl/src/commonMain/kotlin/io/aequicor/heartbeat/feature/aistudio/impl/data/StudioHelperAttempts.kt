package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

internal val HelperAttemptsSpec = KeyValueSpec("ai_studio_helper_attempts", readPolicy = KeyValueReadPolicy.Strict)

/** Whether this invocation acquired the right to prepare a newly journaled request. */
internal data class StudioHelperPreparation(val receipt: StudioHelperReceipt, val isNew: Boolean)

/**
 * Studio's durable evidence of its own native sends, independent of the scheduler/workflow journal. A per-profile
 * lock serializes begin and cancellation, including their writes; no native IO occurs under it. Request ids are
 * immutable: old receipts and cancellation tombstones remain even after a newer turn, preventing delayed replays.
 * A recovered Preparing or Submitting is never automatically submitted again.
 */
@SingleIn(ProfileScope::class)
@Inject
// Preparation, native entry and cancellation must share one atomic journal owner.
@Suppress("TooManyFunctions")
internal class StudioHelperAttempts(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val routes: StudioHelperRoutes = StudioHelperRoutes(),
) {
    private val log = Log.tag("StudioHelperAttempts")
    private val store = stores.keyValue(HelperAttemptsSpec)
    private val lock = Mutex()
    private val chats = stores.keyValue(ChatSpec)

    /** Saves fresh legacy reconciliation before a new writer can replace lastRunRequest. */
    suspend fun rememberLegacy(helper: HelperId, observation: StudioLegacyObservation) = routes.withLock(helper) {
        lock.withLock {
            val record = checkNotNull(chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value })
            check(read(helper)[observation.request.value] == null) { "Legacy request already has a send receipt" }
            store.rememberLegacyObservation(helper, record, observation)
            log.v { "Saved exact legacy observation without a native send receipt" }
        }
    }

    suspend fun legacy(helper: HelperId, request: RequestId): StudioLegacyObservation? = lock.withLock {
        log.v { "Read retained legacy native observation" }
        store.readLegacyObservations(helper)[request.value]
    }

    suspend fun stopLegacy(helper: HelperId, request: RequestId): StudioLegacyObservation? = lock.withLock {
        log.v { "Retained legacy root stop intent" }
        store.stopLegacyObservation(helper, request)
    }

    /** Observation witnesses share this journal lock with send, stop and context replacement decisions. */
    suspend fun prepareRecovery(helper: HelperId, witness: StudioRecoveryWitness): StudioRecoveryWitness =
        routes.withLock(helper) { prepareRecoveryLocked(helper, witness) }

    private suspend fun prepareRecoveryLocked(
        helper: HelperId,
        witness: StudioRecoveryWitness,
    ): StudioRecoveryWitness = lock.withLock {
        require(witness.phase == StudioRecoveryPhase.Inspecting && !witness.isStopRequested)
        require(witness.delivery == null || witness.delivery.chat == helper.value)
        val chat = chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value }
        check(chat == null || chat.ref == witness.session) { "Recovery native context changed before admission" }
        if (witness.route != null) witness.requireRoute(checkNotNull(chat) { "Recovery conversation is missing" })
        val records = store.readRecoveryWitnesses(helper)
        val receipts = read(helper)
        validateRecoveryWorkLineage(records, receipts)
        val resolved = witness.withNativeWorkHead(records, receipts)
        val previous = records[witness.execution.value]
        if (previous != null) {
            return@withLock repeatRecovery(helper, records, previous, resolved)
        }
        val isLegacyStopped = store.readLegacyObservations(helper)[witness.root.value]?.isStopRequested == true
        val transferred = prepareRecoveryTransfer(records, receipts, resolved, isLegacyStopped)
        validateRecoveryWorkLineage(transferred, receipts)
        store.writeRecoveryWitnesses(helper, transferred)
        log.v { "Journaled recovery observation intent" }
        transferred.getValue(witness.execution.value)
    }

    /** Called under the journal lock; a lost preparation ACK can only repeat the same immutable reservation. */
    private suspend fun repeatRecovery(
        helper: HelperId,
        records: Map<String, StudioRecoveryWitness>,
        previous: StudioRecoveryWitness,
        incoming: StudioRecoveryWitness,
    ): StudioRecoveryWitness {
        check(records.values.none { it.predecessorExecution == previous.execution }) {
            "Recovery native ownership was transferred"
        }
        val isStopped = records.values.any { it.root == previous.root && it.isStopRequested } ||
            store.readLegacyObservations(helper)[previous.root.value]?.isStopRequested == true
        val repeated = (if (isStopped) previous.stopped() else previous).repeatDelivery(incoming)
        val changed = records + (incoming.execution.value to repeated)
        validateRecoveryWorkLineage(changed, read(helper))
        if (repeated != previous) store.writeRecoveryWitnesses(helper, changed)
        return repeated
    }

    suspend fun recovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness? = lock.withLock {
        log.v { "Read exact recovery observation witness" }
        store.readRecoveryWitnesses(helper)[execution.value]
    }

    suspend fun recoveryWork(helper: HelperId, root: RequestId): StudioRecoveryWorkResolution = lock.withLock {
        log.v { "Resolve native work head and current observer from both journals" }
        resolveRecoveryWork(root, read(helper), store.readRecoveryWitnesses(helper))
    }

    /** Missing recovery evidence is uncertainty, never a NotSubmitted tombstone. */
    suspend fun stopRecovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness {
        val targets = stopRecoveryTargets(helper, execution)
        check(targets.isNotEmpty()) { "Recovery observation evidence is missing" }
        return checkNotNull(recovery(helper, execution))
    }

    /**
     * Resolves cold Stop by execution or proven native request under the same lock as send cancellation. Candidate
     * membership only fences an uncertain observation; it never authorizes native IO. Multiple witnesses remain
     * ambiguous instead of selecting an arbitrary observation after restart.
     */
    suspend fun stopRecoveryTargets(helper: HelperId, request: RequestId): List<StudioRecoveryWitness> = lock.withLock {
        val records = store.readRecoveryWitnesses(helper)
        val receipts = read(helper)
        val plan = recoveryStopPlan(records, receipts, request)
        if (plan.recoveries != records) store.writeRecoveryWitnesses(helper, plan.recoveries)
        if (plan.receipts != receipts) write(helper, plan.receipts)
        log.v { "Journaled exact recovery stop targets and native descendants" }
        plan.targets
    }

    suspend fun submitRecovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness =
        changeRecovery(helper, execution) { it.submitting() }

    suspend fun confirmRecoveryIdle(
        helper: HelperId,
        execution: RequestId,
        session: SessionRef,
    ): StudioRecoveryWitness = changeRecovery(helper, execution) { it.idle(session) }

    /** An entered sender may close recovery only after its own durable native rejection, never missing evidence. */
    suspend fun retiredRecoverySender(delivery: StudioRecoveryDelivery, claim: String): StudioRecoveryNoSendProof? =
        lock.withLock {
            val helper = HelperId(delivery.chat)
            val witness = store.readRecoveryWitnesses(helper)[delivery.execution.value]
            val receipt = read(helper)[delivery.execution.value]
            val isOwnDelivery = witness?.delivery == delivery && witness.observerSuccessor == null
            val isOwnRejection = receipt?.phase == StudioHelperPhase.NotSubmitted && receipt.submissionClaim == claim
            log.v { "Checked exact recovery sender rejection" }
            if (isOwnDelivery && isOwnRejection) {
                StudioRecoveryNoSendProof(delivery, StudioRecoveryRefusal.Cancelled, claim)
            } else {
                null
            }
        }

    /** Gate proof closes a delivery only; it never creates or changes a native send receipt. */
    suspend fun closeRecoveryNoSend(helper: HelperId, proof: StudioRecoveryNoSendProof): StudioRecoveryWitness {
        check(helper.value == proof.delivery.chat) { "NoSend belongs to another conversation" }
        return changeRecovery(helper, proof.delivery.execution) { witness ->
            val receipt = read(helper)[proof.delivery.execution.value]
            val isRevoked = receipt == null || receipt.phase == StudioHelperPhase.NotSubmitted
            check(proof.senderClaim == null || receipt?.submissionClaim == proof.senderClaim) {
                "Recovery rejection belongs to another sender"
            }
            check(witness.phase != StudioRecoveryPhase.Submit || isRevoked) {
                "Recovery sender still owns native entry"
            }
            witness.noSend(proof, isNewSendRevoked = isRevoked)
        }
    }

    suspend fun observeRecovery(
        helper: HelperId,
        execution: RequestId,
        session: SessionRef,
        request: RequestId,
        turn: TurnId?,
        outcome: StudioHelperTerminalOutcome? = null,
    ): StudioRecoveryWitness = changeRecovery(helper, execution) { it.observed(request, session, turn, outcome) }

    suspend fun loseRecovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness =
        changeRecovery(helper, execution) { it.unknown() }

    /** Production observers mutate only their reserved delivery, atomically with ownership and stop checks. */
    suspend fun changeRecoveryDelivery(
        delivery: StudioRecoveryDelivery,
        change: (StudioRecoveryWitness) -> StudioRecoveryWitness,
    ): StudioRecoveryWitness = changeRecovery(HelperId(delivery.chat), delivery.execution) { witness ->
        if (witness.delivery != delivery) throw CancellationException("Recovery delivery ownership changed")
        change(witness).also(witness::requireSameReservation)
    }

    private suspend fun changeRecovery(
        helper: HelperId,
        execution: RequestId,
        change: suspend (StudioRecoveryWitness) -> StudioRecoveryWitness,
    ): StudioRecoveryWitness = lock.withLock {
        val records = store.readRecoveryWitnesses(helper)
        val previous = checkNotNull(records[execution.value]) { "Recovery observation evidence is missing" }
        if (previous.observerSuccessor != null || records.values.any { it.predecessorExecution == execution } ||
            previous.isObservationRevoked(read(helper))
        ) {
            throw CancellationException("Recovery observation ownership was transferred")
        }
        val isStopped = records.values.any { it.root == previous.root && it.isStopRequested }
        val changed = change(if (isStopped) previous.stopped() else previous)
        if (changed != previous) store.writeRecoveryWitnesses(helper, records + (execution.value to changed))
        log.v { "Journaled recovery observation transition" }
        changed
    }

    suspend fun effectiveReceipt(helper: HelperId, request: RequestId): StudioHelperReceipt? =
        continuation(helper, request)?.head

    suspend fun continuation(helper: HelperId, request: RequestId): StudioContinuationResolution? = lock.withLock {
        log.v { "Resolve validated continuation ancestry" }
        resolveContinuation(read(helper), request)
    }

    /** The route and journal locks serialize this edge against every send and Stop. */
    suspend fun prepareContinuation(
        helper: HelperId,
        request: StudioTurnRequest,
        producer: WorktreeTask,
    ): StudioHelperPreparation = routes.withLock(helper) {
        lock.withLock {
            requireContinuationClaim(producer, request)
            val records = read(helper)
            val source = checkNotNull(request.continuationOf)
            requireRecordedHistory(helper, records)
            requireNativeAdmission(helper, records, request.request, records[source.value]?.policy)
            val record = checkNotNull(chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value })
            checkNotNull(records[source.value]?.executionSnapshot).requireContinuationConnection(record)
            val prompt = HelperPrompt(request.request, request.prompt, directives = request.directives)
            val prepared = prepareContinuationReceipt(records, source, prompt)
            if (prepared.isNew) write(helper, records + (request.request.value to prepared.receipt))
            log.v { "Prepared continuation from exact producer claim" }
            prepared
        }
    }

    /** Recovery authorization and native work edge are persisted together, never inferred from graph map order. */
    suspend fun prepareGraphContinuation(helper: HelperId, request: StudioTurnRequest): StudioHelperPreparation =
        routes.withLock(helper) {
            lock.withLock {
                val records = read(helper)
                requireRecordedHistory(helper, records)
                val witness = checkNotNull(store.readRecoveryWitnesses(helper)[request.request.value])
                val record = checkNotNull(chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value })
                witness.requireRoute(record)
                val prepared = prepareGraphContinuationReceipt(records, witness, request)
                val changed = records + (request.request.value to prepared.receipt)
                requireNativeAdmission(helper, changed, request.request, StudioAttemptPolicy.GraphWork(witness.root))
                if (prepared.isNew) write(helper, changed)
                log.v { "Prepared graph native continuation from exact fresh evidence" }
                prepared
            }
        }

    suspend fun prepare(helper: HelperId, prompt: HelperPrompt): StudioHelperPreparation =
        prepare(helper, prompt, isRedelivery = false, policy = null)

    suspend fun prepareConversation(helper: HelperId, prompt: HelperPrompt): StudioHelperPreparation =
        prepare(helper, prompt, isRedelivery = false, policy = StudioAttemptPolicy.Conversation)

    suspend fun prepareWork(
        helper: HelperId,
        prompt: HelperPrompt,
        policy: StudioAttemptPolicy.OwnedHelperWork,
    ): StudioHelperPreparation = prepare(helper, prompt, isRedelivery = false, policy = policy)

    /**
     * Prepares a host delivery whose stable request may arrive again after its previous preparation ended before
     * native send: rolled back by its own gate (a deferred or cancelled scheduled wake) or abandoned in Preparing by
     * a closed profile. Only such a receipt is prepared anew, even when its text changed: a wake's visible line
     * names the cause of this delivery (an event, then its deadline after a deferral), and nothing was sent natively
     * under the old text. A tombstone written before the prompt arrived and a final external revocation stay final.
     * Callers are serialized by the chat's execution reservation, so the earlier sender has already left;
     * Submitting and later phases are never prepared again and keep their text immutable.
     */
    suspend fun prepareDelivery(
        helper: HelperId,
        prompt: HelperPrompt,
        policy: StudioAttemptPolicy = StudioAttemptPolicy.Conversation,
    ): StudioHelperPreparation {
        check(policy !is StudioAttemptPolicy.OwnedHelperWork) { "Helper work requires lease preparation" }
        return prepare(helper, prompt, isRedelivery = true, policy = policy)
    }

    private suspend fun prepare(
        helper: HelperId,
        prompt: HelperPrompt,
        isRedelivery: Boolean,
        policy: StudioAttemptPolicy?,
    ): StudioHelperPreparation = routes.withLock(helper) {
        prepareLocked(helper, prompt, isRedelivery, policy)
    }

    private suspend fun prepareLocked(
        helper: HelperId,
        prompt: HelperPrompt,
        isRedelivery: Boolean,
        policy: StudioAttemptPolicy?,
    ): StudioHelperPreparation = lock.withLock {
        val records = read(helper)
        requireRecordedHistory(helper, records)
        requireNativeAdmission(helper, records, prompt.request, policy)
        check(
            prompt.request.value !in store.readLegacyObservations(helper),
        ) { "A legacy native request cannot be replayed" }
        val fingerprint = prompt.fingerprint()
        val existing = records[prompt.request.value]
        check(existing?.policy == null || existing.policy.hasSameOrigin(policy)) { "Request attribution cannot change" }
        val isRedelivered = isRedelivery && existing?.policy == policy && existing?.isRedeliveryOf(prompt) == true
        if (existing != null && !isRedelivered) {
            check(
                existing.fingerprint == null ||
                    (existing.fingerprint == fingerprint && existing.handoff == prompt.handoff),
            ) {
                "A helper request cannot change after it was journaled"
            }
            return@withLock StudioHelperPreparation(existing, isNew = false)
        }
        check(chats.get(ChatsKey).orEmpty().firstOrNull { it.id == helper.value }?.pendingEngineSwitch == null) {
            "Helper engine replacement must recover before preparing another request"
        }
        check(records.unresolvedHeads().none { it.request != prompt.request }) {
            "The previous helper request must be reconciled first"
        }
        val receipt = StudioHelperReceipt(
            prompt.request,
            StudioHelperPhase.Preparing,
            fingerprint,
            handoff = prompt.handoff,
            isNativeEntryTracked = true,
            policy = policy,
        )
        write(helper, records + (prompt.request.value to receipt))
        log.v { "Journaled helper preparation redelivered=$isRedelivered" }
        StudioHelperPreparation(receipt, isNew = true)
    }

    /**
     * Binds an opened native session before any context handoff. A repeated bind is idempotent only for the exact
     * same reference. Cancellation and binding share the journal lock; null means preparation is already revoked
     * or has crossed its submission boundary. Binding alone never authorizes sending a prompt.
     */
    suspend fun bindSession(helper: HelperId, request: RequestId, session: SessionRef): StudioHelperReceipt? =
        lock.withLock {
            requireRecoveryOwner(helper, request)
            val records = read(helper)
            val receipt = checkNotNull(records[request.value]) { "The helper request was not journaled" }
            if (receipt.phase != StudioHelperPhase.Preparing) return@withLock null
            check(receipt.preparedSession == null || receipt.preparedSession == session) {
                "A helper preparation cannot change its native session"
            }
            if (receipt.preparedSession == session) return@withLock receipt
            val prepared = receipt.copy(preparedSession = session)
            write(helper, records + (request.value to prepared))
            log.v { "Journaled helper target before context admission" }
            prepared
        }

    /** A successful return owns the only permission to send R; persistence precedes any native side effect. */
    suspend fun begin(helper: HelperId, request: RequestId, claim: String? = null): Boolean = lock.withLock {
        val records = read(helper)
        requireNativeAdmission(helper, records, request)
        val receipt = records[request.value]
        if (receipt?.phase != StudioHelperPhase.Preparing) return@withLock false
        check(receipt.handoff == null || receipt.preparedSession != null) { "Helper context has no bound target" }
        write(
            helper,
            records + (request.value to receipt.copy(phase = StudioHelperPhase.Submitting, submissionClaim = claim)),
        )
        log.v { "Journaled helper submission boundary" }
        true
    }

    /** Actual configuration and provenance must survive a crash after native entry. */
    suspend fun recordExecutionSnapshot(
        helper: HelperId,
        request: RequestId,
        claim: String,
        snapshot: StudioExecutionSnapshot,
        nativeRoute: StudioRecoveryRoute? = null,
    ): StudioExecutionSnapshot = lock.withLock {
        val records = read(helper)
        requireNativeAdmission(helper, records, request)
        val receipt = checkNotNull(records[request.value]) { "Submission receipt is missing" }
        val frozen = receipt.freezeExecutionSnapshot(claim, snapshot, nativeRoute)
        write(helper, records + (request.value to frozen))
        log.v { "Persisted exact submission configuration and authority" }
        snapshot
    }

    /**
     * Only the exact local sender, before entering native send or after the engine definitively refused it, may
     * retire its own durable claim; a sender without a bound session passes null. External cancel must keep using
     * cancelBeforeSubmission. A lost begin ACK is safe: the persisted claim identifies its owner; another sender's
     * Submitting is never downgraded. A failed cleanup write leaves the attempt uncertain.
     */
    suspend fun cancelBeforeNative(helper: HelperId, request: RequestId, session: SessionRef?, claim: String): Boolean =
        lock.withLock {
            requireRecoveryOwner(helper, request)
            val records = read(helper)
            if (records.isSupersededAttempt(request)) {
                return@withLock false
            }
            val receipt = checkNotNull(records[request.value]) { "Missing helper preparation" }
            if (receipt.phase == StudioHelperPhase.NotSubmitted) return@withLock true
            if (!receipt.ownsUnsentBoundary(session, claim)) return@withLock false
            write(helper, records + (request.value to receipt.copy(phase = StudioHelperPhase.NotSubmitted)))
            log.v { "Exact helper sender confirmed no native submission" }
            true
        }

    /**
     * Unknown ids also receive a durable tombstone, so a prompt arriving later cannot enter begin. An external
     * barrier is [isFinal]: its revocation is never prepared again, even by a redelivery of the same request.
     * Only the exact gate that prepared the request passes false, rolling back its own preparation.
     */
    suspend fun cancelBeforeSubmission(
        helper: HelperId,
        request: RequestId,
        isFinal: Boolean = true,
    ): StudioHelperReceipt = lock.withLock {
        val records = read(helper)
        fenceRecoveryCancellation(helper, request, records, isExplicitStop = isFinal)
        requireRecordedHistory(helper, records)
        val (changed, cancelled) = cancelPreparationReceipt(records, request, isFinal)
        if (changed != records) write(helper, changed)
        log.v { "Journaled helper cancellation before submission final=${cancelled.isFinal}" }
        cancelled
    }

    suspend fun accepted(
        helper: HelperId,
        request: RequestId,
        session: SessionRef,
        turn: TurnId,
        isVirtualContinuation: Boolean = false,
    ) = lock.withLock {
        val records = read(helper)
        val receipt = checkNotNull(records[request.value]) { "The native request was not journaled before submission" }
        check(!isVirtualContinuation || receipt.continuationOf != null) {
            "A virtual map cannot create continuation authority"
        }
        receipt.requireNativeIdentity(session, turn)
        check(receipt.phase in NATIVE_PHASES) { "A revoked helper request cannot be accepted" }
        if (receipt.phase == StudioHelperPhase.Terminal) return@withLock
        val accepted = receipt.copy(phase = StudioHelperPhase.Accepted, session = session, turn = turn)
        write(helper, records + (request.value to accepted))
        log.v { "Journaled helper native acceptance" }
    }

    /** Only the native observer, with exact request/turn evidence, may call this operation. */
    suspend fun terminal(helper: HelperId, request: RequestId, evidence: StudioHelperTerminal) = lock.withLock {
        val records = read(helper)
        val receipt = checkNotNull(records[request.value]) { "The helper request was not journaled" }
        receipt.requireNativeIdentity(evidence.session, evidence.turn)
        check(receipt.phase in NATIVE_PHASES) { "A revoked helper request cannot have a native result" }
        val terminal = receipt.terminal?.enrichedBy(evidence) ?: evidence.copy(turn = receipt.turn ?: evidence.turn)
        val completed = receipt.copy(
            phase = StudioHelperPhase.Terminal,
            session = evidence.session,
            turn = terminal.turn,
            terminal = terminal,
        )
        write(helper, records + (request.value to completed))
        log.v { "Journaled confirmed helper terminal result" }
    }

    suspend fun receipt(helper: HelperId, request: RequestId): StudioHelperReceipt? = lock.withLock {
        log.v { "Read helper request receipt" }
        read(helper)[request.value]
    }

    /** Binds immutable source evidence before any native effect; caller values are never decoded from model input. */
    suspend fun bindCauses(helper: HelperId, request: RequestId, causes: Set<RequestInitiator>): StudioHelperReceipt =
        lock.withLock {
            val records = read(helper)
            val receipt = checkNotNull(records[request.value]) { "The conversation request was not journaled" }
            check(receipt.phase == StudioHelperPhase.Preparing) { "Source evidence must precede submission" }
            check(receipt.causes == null || receipt.causes == causes) { "Conversation source evidence changed" }
            receipt.copy(causes = causes).also { write(helper, records + (request.value to it)) }
        }

    /** The actual sender boundary: an external stop before it prevents all later native delivery. */
    suspend fun enterNative(helper: HelperId, request: RequestId, claim: String): Boolean = lock.withLock {
        val records = read(helper)
        requireNativeAdmission(helper, records, request)
        val receipt = checkNotNull(records[request.value]) { "The conversation request was not journaled" }
        val isOwned = receipt.phase == StudioHelperPhase.Submitting && receipt.submissionClaim == claim
        if (!isOwned || receipt.isStopRequested || receipt.nativeEntryClaim != null) return@withLock false
        check(receipt.isNativeEntryTracked) { "Legacy submission needs reconciliation before another native entry" }
        write(helper, records + (request.value to receipt.copy(nativeEntryClaim = claim)))
        true
    }

    /**
     * Fences preparation and persists stop intent for an entered/legacy attempt. The native adapter must still
     * confirm its terminal result. A fresh Submitting whose sender has not entered native code is safe to retire.
     */
    suspend fun requestStop(
        helper: HelperId,
        request: RequestId,
        shouldStopNative: Boolean = true,
    ): StudioHelperReceipt = lock.withLock {
        val records = read(helper)
        fenceRecoveryCancellation(helper, request, records, isExplicitStop = shouldStopNative)
        requireRecordedHistory(helper, records)
        val (fenced, stopped) = stopContinuationChain(records, request, shouldStopNative)
        write(helper, fenced)
        stopped
    }

    /** Called under the journal lock; a new-send fallback cannot retire a recovery observer. */
    private suspend fun fenceRecoveryCancellation(
        helper: HelperId,
        request: RequestId,
        receipts: Map<String, StudioHelperReceipt>,
        isExplicitStop: Boolean,
    ) {
        val old = if (isExplicitStop) {
            store.stopLegacyObservation(helper, request)
        } else {
            store.readLegacyObservations(helper)[request.value]
        }
        val records = store.readRecoveryWitnesses(helper)
        val linked = recoveryStopChain(records, request)
        if (isExplicitStop && linked.isNotEmpty()) {
            store.writeRecoveryWitnesses(helper, records + linked.associate { it.execution.value to it.stopped() })
        }
        val targets = linked.filterNot {
            it.execution == request && (
                (it.phase == StudioRecoveryPhase.Submit && request.value in receipts) ||
                    (
                        it.phase == StudioRecoveryPhase.NoSend && it.observed != request &&
                            receipts[request.value]?.phase == StudioHelperPhase.NotSubmitted
                    )
            )
        }
        if (targets.isEmpty() && (old == null || request.value in receipts)) return
        throw EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request))
    }

    /** Called under the journal lock; a retired observer cannot bind or retire its former send preparation. */
    private suspend fun requireRecoveryOwner(helper: HelperId, request: RequestId) {
        val recoveries = store.readRecoveryWitnesses(helper)
        if (recoveries[request.value]?.observerSuccessor != null ||
            recoveries.values.any { it.predecessorExecution == request }
        ) {
            throw CancellationException("Recovery observation ownership was transferred")
        }
    }

    suspend fun unresolved(helper: HelperId): RequestId? = lock.withLock {
        log.v { "Read unresolved helper request" }
        val pending = read(helper).unresolvedHeads()
        check(pending.size <= 1) { "Contradictory helper request receipts" }
        pending.singleOrNull()?.request
    }

    /**
     * Context replacement excludes every unresolved attempt. Only this local submission's unbound Conversation
     * preparation may travel with its own request before native policy preparation starts. A caller additionally
     * holds the route mutex and proves that the coordinator still reserves this exact request.
     */
    suspend fun isContextIdle(helper: HelperId, preparing: StudioHelperSubmission? = null): Boolean = lock.withLock {
        log.v { "Read durable context replacement evidence" }
        val records = read(helper)
        if (!hasRecordedHistory(helper, records)) return@withLock false
        if (store.readRecoveryWitnesses(
                helper,
            ).activeRecoveryWitnesses(records).any { it.blocksContext(records) }
        ) {
            return@withLock false
        }
        if (preparing != null) {
            val own = records.values.singleOrNull { preparing.matches(helper, it.request) }
            if (own?.isUnboundConversationPreparation != true) return@withLock false
        }
        records.unresolvedHeads().all {
            preparing?.matches(helper, it.request) == true && it.isUnboundConversationPreparation
        }
    }

    /** Unclassified/missing old evidence is visible but can never choose a lease's work request. */
    suspend fun workOwnership(helper: HelperId, owner: ActionId, lastKnown: RequestId?): StudioWorkOwnership =
        lock.withLock {
            log.v { "Read exact helper work attribution" }
            val records = read(helper)
            val pending = records.unresolvedHeads()
            check(pending.size <= 1) { "Contradictory conversation attempts" }
            val owned = pending.singleOrNull()?.policy as? StudioAttemptPolicy.OwnedHelperWork
            val isResolved = pending.all { it.policy != null && it.policy != StudioAttemptPolicy.UnknownService } &&
                (lastKnown == null || lastKnown.value in records) &&
                (owned == null || owned.binding.owner == owner)
            StudioWorkOwnership(pending.singleOrNull()?.request?.takeIf { owned?.binding?.owner == owner }, isResolved)
        }

    /** A trusted producer's exact journal may classify legacy evidence, without changing native execution state. */
    suspend fun reconcileWork(
        helper: HelperId,
        request: RequestId,
        policy: StudioAttemptPolicy.OwnedHelperWork,
        isNeverStarted: Boolean = false,
    ): Boolean = routes.withLock(helper) {
        reconcileWorkLocked(helper, request, policy, isNeverStarted)
    }

    private suspend fun reconcileWorkLocked(
        helper: HelperId,
        request: RequestId,
        policy: StudioAttemptPolicy.OwnedHelperWork,
        isNeverStarted: Boolean,
    ): Boolean = lock.withLock {
        log.v { "Reconcile work attribution from its exact producer journal" }
        val records = read(helper)
        val existing = records[request.value]
        val receipt = reconcileWorkReceipt(existing, request, policy, isNeverStarted) ?: return@withLock false
        if (receipt != existing) write(helper, records + (request.value to receipt))
        true
    }

    suspend fun awaitSubmissionBoundary(helper: HelperId, request: RequestId): StudioHelperPhase =
        store.observe(helperReceiptKey(helper)).map { decodeHelperReceipts(it)[request.value]?.phase }.first {
            it != null && it != StudioHelperPhase.Preparing
        }.let { checkNotNull(it) }

    /** New senders remain revocable throughout policy preparation, until their actual native entry is journaled. */
    suspend fun awaitNativeEntryBoundary(helper: HelperId, request: RequestId): StudioHelperPhase =
        store.observe(helperReceiptKey(helper)).map { decodeHelperReceipts(it)[request.value] }.first { receipt ->
            receipt != null && when (receipt.phase) {
                StudioHelperPhase.Preparing -> false
                StudioHelperPhase.Submitting -> !receipt.isNativeEntryTracked || receipt.nativeEntryClaim != null
                StudioHelperPhase.Accepted, StudioHelperPhase.Terminal, StudioHelperPhase.NotSubmitted -> true
            }
        }.let { checkNotNull(it).phase }

    private suspend fun read(helper: HelperId): Map<String, StudioHelperReceipt> = decodeHelperReceipts(
        store.get(helperReceiptKey(helper)),
    )

    /** Missing old evidence preserves readable history, but cannot authorize another send or a safe tombstone. */
    private suspend fun requireNativeAdmission(
        helper: HelperId,
        receipts: Map<String, StudioHelperReceipt>,
        request: RequestId,
        incoming: StudioAttemptPolicy? = null,
    ) {
        store.requireNativeAdmission(helper, receipts, request, incoming)
    }

    private suspend fun requireRecordedHistory(helper: HelperId, records: Map<String, StudioHelperReceipt>) {
        check(hasRecordedHistory(helper, records)) {
            "Conversation attempt evidence is missing; reconcile before another submission"
        }
    }

    private suspend fun hasRecordedHistory(helper: HelperId, records: Map<String, StudioHelperReceipt>): Boolean {
        val record = chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value }
        return record.hasRecordedAttemptHistory(records)
    }

    private suspend fun write(helper: HelperId, records: Map<String, StudioHelperReceipt>) {
        store.set(helperReceiptKey(helper), Json.encodeToString(records))
    }

    /**
     * A delivery of the same request whose earlier preparation never crossed the native submission boundary and was
     * not revoked by an external barrier. Its text may differ (see [prepareDelivery]); its handoff may not.
     */
    private fun StudioHelperReceipt.isRedeliveryOf(prompt: HelperPrompt): Boolean =
        phase in REPREPARABLE_PHASES && !isFinal && fingerprint != null && handoff == prompt.handoff

    private companion object {
        val NATIVE_PHASES = setOf(StudioHelperPhase.Submitting, StudioHelperPhase.Accepted, StudioHelperPhase.Terminal)
        val REPREPARABLE_PHASES = setOf(StudioHelperPhase.Preparing, StudioHelperPhase.NotSubmitted)
    }
}

private val StudioHelperReceipt.isUnboundConversationPreparation: Boolean
    get() = policy == StudioAttemptPolicy.Conversation && phase == StudioHelperPhase.Preparing &&
        preparedSession == null && submissionClaim == null && nativeEntryClaim == null && !isStopRequested

/** Directives are part of the immutable attempt; older receipts without them keep their original fingerprint. */
internal fun HelperPrompt.fingerprint(): String {
    val directed = directives.joinToString("") { "\u0000$it" }
    return "$isRecovery:$text$directed".encodeUtf8().sha256().hex()
}
