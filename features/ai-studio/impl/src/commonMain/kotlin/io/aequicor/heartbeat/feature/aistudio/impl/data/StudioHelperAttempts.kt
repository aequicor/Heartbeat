package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

internal val HelperAttemptsSpec = KeyValueSpec("ai_studio_helper_attempts")

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
internal class StudioHelperAttempts(
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val log = Log.tag("StudioHelperAttempts")
    private val store = stores.keyValue(HelperAttemptsSpec)
    private val lock = Mutex()

    suspend fun prepare(helper: HelperId, prompt: HelperPrompt): StudioHelperPreparation =
        prepare(helper, prompt, isRedelivery = false)

    /**
     * Prepares a host delivery whose stable request may arrive again after its previous preparation ended before
     * native send: rolled back by its own gate (a deferred or cancelled scheduled wake) or abandoned in Preparing by
     * a closed profile. Only such a receipt is prepared anew, even when its text changed: a wake's visible line
     * names the cause of this delivery (an event, then its deadline after a deferral), and nothing was sent natively
     * under the old text. A tombstone written before the prompt arrived and a final external revocation stay final.
     * Callers are serialized by the chat's execution reservation, so the earlier sender has already left;
     * Submitting and later phases are never prepared again and keep their text immutable.
     */
    suspend fun prepareDelivery(helper: HelperId, prompt: HelperPrompt): StudioHelperPreparation =
        prepare(helper, prompt, isRedelivery = true)

    private suspend fun prepare(
        helper: HelperId,
        prompt: HelperPrompt,
        isRedelivery: Boolean,
    ): StudioHelperPreparation = lock.withLock {
        val records = read(helper)
        val fingerprint = prompt.fingerprint()
        val existing = records[prompt.request.value]
        val isRedelivered = isRedelivery && existing?.isRedeliveryOf(prompt) == true
        if (existing != null && !isRedelivered) {
            check(
                existing.fingerprint == null ||
                    (existing.fingerprint == fingerprint && existing.handoff == prompt.handoff),
            ) {
                "A helper request cannot change after it was journaled"
            }
            return@withLock StudioHelperPreparation(existing, isNew = false)
        }
        check(records.values.none { it.isUnresolved && it.request != prompt.request }) {
            "The previous helper request must be reconciled first"
        }
        val receipt = StudioHelperReceipt(
            prompt.request,
            StudioHelperPhase.Preparing,
            fingerprint,
            handoff = prompt.handoff,
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
        val receipt = records[request.value]
        val isFinalized = receipt?.phase == StudioHelperPhase.NotSubmitted && isFinal && !receipt.isFinal
        if (receipt != null && receipt.phase != StudioHelperPhase.Preparing && !isFinalized) return@withLock receipt
        val cancelled = receipt?.copy(phase = StudioHelperPhase.NotSubmitted, isFinal = isFinal || receipt.isFinal)
            ?: StudioHelperReceipt(request, StudioHelperPhase.NotSubmitted, null, isFinal = isFinal)
        write(helper, records + (request.value to cancelled))
        log.v { "Journaled helper cancellation before submission final=${cancelled.isFinal}" }
        cancelled
    }

    suspend fun accepted(helper: HelperId, request: RequestId, session: SessionRef, turn: TurnId) = lock.withLock {
        val records = read(helper)
        val receipt = checkNotNull(records[request.value]) { "The helper request was not journaled" }
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

    suspend fun unresolved(helper: HelperId): RequestId? = lock.withLock {
        log.v { "Read unresolved helper request" }
        val pending = read(helper).values.filter { it.isUnresolved }
        check(pending.size <= 1) { "Contradictory helper request receipts" }
        pending.singleOrNull()?.request
    }

    suspend fun awaitSubmissionBoundary(helper: HelperId, request: RequestId): StudioHelperPhase =
        store.observe(key(helper)).map { decode(it)[request.value]?.phase }.first {
            it != null && it != StudioHelperPhase.Preparing
        }.let { checkNotNull(it) }

    private suspend fun read(helper: HelperId): Map<String, StudioHelperReceipt> = decode(store.get(key(helper)))

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

/** Directives are part of the immutable attempt; older receipts without them keep their original fingerprint. */
private fun HelperPrompt.fingerprint(): String {
    val directed = directives.joinToString("") { "\u0000$it" }
    return "$isRecovery:$text$directed".encodeUtf8().sha256().hex()
}

/** Receipt decoder errors can include a persisted answer; omit its text and cause. */
private fun Exception.withoutHelperReceiptText(): IllegalStateException =
    IllegalStateException("Unreadable helper receipts (${this::class.simpleName.orEmpty()})")
