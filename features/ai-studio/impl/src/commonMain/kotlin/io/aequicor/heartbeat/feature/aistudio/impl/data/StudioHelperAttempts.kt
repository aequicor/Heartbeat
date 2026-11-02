package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
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

    /** Observation witnesses share this journal lock with send, stop and context replacement decisions. */
    suspend fun prepareRecovery(helper: HelperId, witness: StudioRecoveryWitness): StudioRecoveryWitness =
        routes.withLock(helper) { prepareRecoveryLocked(helper, witness) }

    private suspend fun prepareRecoveryLocked(
        helper: HelperId,
        witness: StudioRecoveryWitness,
    ): StudioRecoveryWitness = lock.withLock {
        require(witness.phase == StudioRecoveryPhase.Inspecting && !witness.isStopRequested)
        val chat = chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value }
        check(chat == null || chat.ref == witness.session) { "Recovery native context changed before admission" }
        val records = readRecoveries(helper)
        val previous = records[witness.execution.value]
        if (previous != null) {
            check(
                previous.root == witness.root && previous.candidates == witness.candidates &&
                    previous.session == witness.session,
            ) { "Recovery execution identity changed" }
            return@withLock previous
        }
        val receipts = read(helper)
        check(records.values.none { it.blocksContext(receipts) }) { "Previous recovery must be reconciled first" }
        writeRecoveries(helper, records + (witness.execution.value to witness))
        log.v { "Journaled recovery observation intent" }
        witness
    }

    suspend fun recovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness? = lock.withLock {
        log.v { "Read exact recovery observation witness" }
        readRecoveries(helper)[execution.value]
    }

    /** Missing recovery evidence is uncertainty, never a NotSubmitted tombstone. */
    suspend fun stopRecovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness =
        changeRecovery(helper, execution) { it.stopped() }

    suspend fun submitRecovery(helper: HelperId, execution: RequestId): StudioRecoveryWitness =
        changeRecovery(helper, execution) { it.submitting() }

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

    private suspend fun changeRecovery(
        helper: HelperId,
        execution: RequestId,
        change: (StudioRecoveryWitness) -> StudioRecoveryWitness,
    ): StudioRecoveryWitness = lock.withLock {
        val records = readRecoveries(helper)
        val previous = checkNotNull(records[execution.value]) { "Recovery observation evidence is missing" }
        val changed = change(previous)
        if (changed != previous) writeRecoveries(helper, records + (execution.value to changed))
        log.v { "Journaled recovery observation transition" }
        changed
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
        requireRecoveryAdmission(readRecoveries(helper).values, records, prompt.request)
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
        check(records.values.none { it.isUnresolved && it.request != prompt.request }) {
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

    /**
     * Only the exact local sender, before entering native send or after the engine definitively refused it, may
     * retire its own durable claim; a sender without a bound session passes null. External cancel must keep using
     * cancelBeforeSubmission. A lost begin ACK is safe: the persisted claim identifies its owner; another sender's
     * Submitting is never downgraded. A failed cleanup write leaves the attempt uncertain.
     */
    suspend fun cancelBeforeNative(helper: HelperId, request: RequestId, session: SessionRef?, claim: String): Boolean =
        lock.withLock {
            val records = read(helper)
            val receipt = checkNotNull(records[request.value]) { "Missing helper preparation" }
            if (receipt.phase == StudioHelperPhase.NotSubmitted) return@withLock true
            if ((receipt.preparedSession != null && receipt.preparedSession != session) ||
                receipt.session != null
            ) {
                return@withLock false
            }
            val isBoundaryOwned = receipt.phase == StudioHelperPhase.Preparing ||
                (receipt.phase == StudioHelperPhase.Submitting && receipt.submissionClaim == claim)
            if (!isBoundaryOwned) return@withLock false
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
        val receipt = records[request.value]
        val isFinalized = receipt?.phase == StudioHelperPhase.NotSubmitted && isFinal && !receipt.isFinal
        val isBeforeNative = receipt?.phase == StudioHelperPhase.Preparing ||
            (
                receipt?.phase == StudioHelperPhase.Submitting && receipt.isNativeEntryTracked &&
                    receipt.nativeEntryClaim == null
            )
        if (receipt != null && !isBeforeNative && !isFinalized) return@withLock receipt
        val cancelled = receipt?.copy(phase = StudioHelperPhase.NotSubmitted, isFinal = isFinal || receipt.isFinal)
            ?: StudioHelperReceipt(request, StudioHelperPhase.NotSubmitted, null, isFinal = isFinal)
        write(helper, records + (request.value to cancelled))
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
        val receipt = records[request.value] ?: if (isVirtualContinuation) {
            StudioHelperReceipt(
                request,
                StudioHelperPhase.Accepted,
                "virtual-continuation:${request.value}",
                session,
                turn,
            )
        } else {
            error("The helper request was not journaled")
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
        if (receipt.phase == StudioHelperPhase.Terminal) {
            check(receipt.terminal == evidence) { "A confirmed helper result cannot change" }
            return@withLock
        }
        val completed = receipt.copy(
            phase = StudioHelperPhase.Terminal,
            session = evidence.session,
            turn = evidence.turn,
            terminal = evidence,
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
    suspend fun requestStop(helper: HelperId, request: RequestId): StudioHelperReceipt = lock.withLock {
        val records = read(helper)
        fenceRecoveryCancellation(helper, request, records, isExplicitStop = true)
        requireRecordedHistory(helper, records)
        val receipt = records[request.value]
        val updated = when {
            receipt == null -> StudioHelperReceipt(request, StudioHelperPhase.NotSubmitted, null, isFinal = true)

            receipt.phase == StudioHelperPhase.Terminal -> receipt

            receipt.phase in setOf(StudioHelperPhase.Preparing, StudioHelperPhase.NotSubmitted) ||
                (
                    receipt.phase == StudioHelperPhase.Submitting && receipt.isNativeEntryTracked &&
                        receipt.nativeEntryClaim == null
                ) ->
                receipt.copy(phase = StudioHelperPhase.NotSubmitted, isFinal = true, isStopRequested = true)

            else -> receipt.copy(isStopRequested = true)
        }
        write(helper, records + (request.value to updated))
        updated
    }

    /** Called under the journal lock; a new-send fallback cannot retire a recovery observer. */
    private suspend fun fenceRecoveryCancellation(
        helper: HelperId,
        request: RequestId,
        receipts: Map<String, StudioHelperReceipt>,
        isExplicitStop: Boolean,
    ) {
        val records = readRecoveries(helper)
        val witness = records[request.value] ?: return
        if (witness.phase == StudioRecoveryPhase.Submit && request.value in receipts) return
        if (isExplicitStop && !witness.isStopRequested) {
            writeRecoveries(helper, records + (request.value to witness.stopped()))
        }
        throw EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request))
    }

    suspend fun unresolved(helper: HelperId): RequestId? = lock.withLock {
        log.v { "Read unresolved helper request" }
        val pending = read(helper).values.filter { it.isUnresolved }
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
        if (readRecoveries(helper).values.any { it.blocksContext(records) }) return@withLock false
        if (preparing != null) {
            val own = records.values.singleOrNull { preparing.matches(helper, it.request) }
            if (own?.isUnboundConversationPreparation != true) return@withLock false
        }
        records.values.filter { it.isUnresolved }.all {
            preparing?.matches(helper, it.request) == true && it.isUnboundConversationPreparation
        }
    }

    /** Unclassified/missing old evidence is visible but can never choose a lease's work request. */
    suspend fun workOwnership(helper: HelperId, owner: ActionId, lastKnown: RequestId?): StudioWorkOwnership =
        lock.withLock {
            log.v { "Read exact helper work attribution" }
            val records = read(helper)
            val pending = records.values.filter { it.isUnresolved }
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
        val prior = existing?.policy
        if (prior != null && prior !is StudioAttemptPolicy.OwnedHelperWork) return@withLock false
        if (prior is StudioAttemptPolicy.OwnedHelperWork) {
            val old = prior.binding
            val incoming = policy.binding
            return@withLock old.owner == incoming.owner &&
                (incoming.reservation == null || old.reservation == null || old.reservation == incoming.reservation)
        }
        val receipt = existing?.copy(policy = policy) ?: if (isNeverStarted) {
            StudioHelperReceipt(request, StudioHelperPhase.NotSubmitted, null, isFinal = true, policy = policy)
        } else {
            StudioHelperReceipt(
                request,
                StudioHelperPhase.Submitting,
                "legacy-work:${policy.binding.owner.value}:${request.value}",
                policy = policy,
            )
        }
        write(helper, records + (request.value to receipt))
        true
    }

    suspend fun awaitSubmissionBoundary(helper: HelperId, request: RequestId): StudioHelperPhase =
        store.observe(key(helper)).map { decode(it)[request.value]?.phase }.first {
            it != null && it != StudioHelperPhase.Preparing
        }.let { checkNotNull(it) }

    /** New senders remain revocable throughout policy preparation, until their actual native entry is journaled. */
    suspend fun awaitNativeEntryBoundary(helper: HelperId, request: RequestId): StudioHelperPhase =
        store.observe(key(helper)).map { decode(it)[request.value] }.first { receipt ->
            receipt != null && when (receipt.phase) {
                StudioHelperPhase.Preparing -> false
                StudioHelperPhase.Submitting -> !receipt.isNativeEntryTracked || receipt.nativeEntryClaim != null
                StudioHelperPhase.Accepted, StudioHelperPhase.Terminal, StudioHelperPhase.NotSubmitted -> true
            }
        }.let { checkNotNull(it).phase }

    private suspend fun read(helper: HelperId): Map<String, StudioHelperReceipt> = decode(store.get(key(helper)))

    private suspend fun readRecoveries(helper: HelperId): Map<String, StudioRecoveryWitness> {
        val raw = store.get(recoveryKey(helper)) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, StudioRecoveryWitness>>(raw).also { records ->
                check(records.all { (key, witness) -> key == witness.execution.value }) {
                    "Mismatched recovery witness key"
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e.withoutHelperReceiptText()
        }
    }

    private suspend fun writeRecoveries(helper: HelperId, records: Map<String, StudioRecoveryWitness>) {
        store.set(recoveryKey(helper), Json.encodeToString(records))
    }

    private fun recoveryKey(helper: HelperId) = stringKey("recovery_${helper.value.encodeUtf8().sha256().hex()}")

    /** Missing old evidence preserves readable history, but cannot authorize another send or a safe tombstone. */
    private suspend fun requireRecordedHistory(helper: HelperId, records: Map<String, StudioHelperReceipt>) {
        check(hasRecordedHistory(helper, records)) {
            "Conversation attempt evidence is missing; reconcile before another submission"
        }
    }

    private suspend fun hasRecordedHistory(helper: HelperId, records: Map<String, StudioHelperReceipt>): Boolean {
        val record = chats.get(ChatsKey).orEmpty().singleOrNull { it.id == helper.value }
        val isTracked = record?.helper != null || record?.identity?.context?.kind in setOf(
            SessionKind.Subsession,
            SessionKind.Subagent,
        )
        val known = record?.lastRunRequest
        return !isTracked || known == null || known.value in records
    }

    private suspend fun write(helper: HelperId, records: Map<String, StudioHelperReceipt>) {
        store.set(key(helper), Json.encodeToString(records))
    }

    private fun key(helper: HelperId) = stringKey("helper_${helper.value.encodeUtf8().sha256().hex()}")

    private fun decode(raw: String?): Map<String, StudioHelperReceipt> {
        if (raw == null) return emptyMap()
        return try {
            Json.decodeFromString<Map<String, StudioHelperReceipt>>(raw).also { records ->
                check(records.all { (key, receipt) -> key == receipt.request.value }) {
                    "Mismatched helper receipt key"
                }
            }
        } catch (e: IllegalArgumentException) {
            // Decoder diagnostics can contain an answer; expose only its kind, without the original text/cause.
            throw e.withoutHelperReceiptText()
        }
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
private fun HelperPrompt.fingerprint(): String {
    val directed = directives.joinToString("") { "\u0000$it" }
    return "$isRecovery:$text$directed".encodeUtf8().sha256().hex()
}

/** Receipt decoder errors can include a persisted answer; omit its text and cause. */
private fun Exception.withoutHelperReceiptText(): IllegalStateException =
    IllegalStateException("Unreadable helper receipts (${this::class.simpleName.orEmpty()})")
