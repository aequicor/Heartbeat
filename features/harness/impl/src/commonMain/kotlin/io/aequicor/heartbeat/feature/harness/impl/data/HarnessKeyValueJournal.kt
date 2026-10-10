package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** Raw feature-owned record; an absolute deadline prevents recovery from extending retention. */
@Serializable
internal data class HarnessRawValue(val text: String, val expiresAt: Instant? = null) {
    override fun toString(): String = "HarnessRawValue(***)"
}

@Serializable
internal enum class HarnessCommitPhase { Prepared, Committed }

/** Recovery result lets an exact retry recognize its previously committed operation. */
internal data class HarnessRecoveredCommit(val operationId: String, val phase: HarnessCommitPhase)

/**
 * Small feature-private multi-key journal. Each repository uses its own store and holds its own mutex across
 * recover, reads, validation and commit. It must never expose a raw read before recovery completes.
 * Prepared recovery restores every before image; Committed recovery restores every after image. The single
 * durable Committed marker is the commit point. A lost marker-write ACK is resolved by reading that marker.
 * No shared core transaction abstraction or nested commit is supported.
 */
internal class HarnessKeyValueJournal(private val store: KeyValueStore, clock: Clock) {
    private val log = Log.tag("HarnessJournal")
    private val key = stringKey("pending")
    private val images = HarnessJournalImages(store, clock)

    suspend fun recover(): HarnessRecoveredCommit? = try {
        readPending()?.let { pending ->
            val values = if (pending.phase == HarnessCommitPhase.Committed) pending.after else pending.before
            images.apply(values)
            images.verify(values)
            clear()
            images.clean(pending)
            log.v { "Recovered harness repository journal" }
            HarnessRecoveredCommit(pending.operationId, pending.phase)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: HarnessStorageCorrupt) {
        throw error
    } catch (error: Exception) {
        log.w(
            HarnessStorageUncertain(),
        ) { "Harness journal recovery remains uncertain (${error::class.simpleName.orEmpty()})" }
        throw HarnessStorageUncertain()
    }

    suspend fun commit(
        operationId: String,
        before: Map<String, HarnessRawValue?>,
        after: Map<String, HarnessRawValue?>,
    ) {
        val prepared = HarnessPendingCommit(
            operationId,
            HarnessCommitPhase.Prepared,
            images.references(operationId, "before", before),
            images.references(operationId, "after", after),
        )
        var isReconciliationRequired = true
        try {
            check(readPending() == null) { "Harness journal must be recovered before a new write" }
            images.verifyRaw(before)
            images.persist(before, prepared.before)
            images.persist(after, prepared.after)
            store.set(key, Json.encodeToString(prepared))
            images.apply(prepared.after)
            store.set(
                key,
                Json.encodeToString(prepared.copy(phase = HarnessCommitPhase.Committed, before = emptyMap())),
            )
            isReconciliationRequired = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            isReconciliationRequired = false
            log.w(
                HarnessStorageUncertain(),
            ) { "Harness write acknowledgement failed (${error::class.simpleName.orEmpty()})" }
            settleFailedWrite(prepared, before)
        } finally {
            if (isReconciliationRequired) withContext(NonCancellable) { reconcileCancellation(prepared, before) }
        }
        // Once committed, failed cleanup cannot turn success into SaveFailed. Recovery will roll forward.
        cleanCommitted(prepared)
    }

    private suspend fun settleFailedWrite(prepared: HarnessPendingCommit, before: Map<String, HarnessRawValue?>) {
        val isCommitted = try {
            val durable = readPending()
            when {
                durable == null -> {
                    images.verifyRaw(before)
                    false
                }

                !durable.matches(prepared) ->
                    uncertainJournalOutcome()

                durable.phase == HarnessCommitPhase.Committed -> true

                else -> {
                    images.apply(prepared.before)
                    images.verify(prepared.before)
                    clear()
                    images.clean(prepared)
                    false
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(
                HarnessStorageUncertain(),
            ) { "Harness write outcome cannot yet be proven (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageUncertain()
        }
        if (!isCommitted) confirmedRollbackFailure()
    }

    private suspend fun reconcileCancellation(prepared: HarnessPendingCommit, before: Map<String, HarnessRawValue?>) =
        withTimeoutOrNull(CANCELLATION_CLEANUP_MILLIS) {
            try {
                settleFailedWrite(prepared, before)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Cancellation remains cancellation; a durable pending marker is retried when the profile reopens.
                log.w(
                    HarnessStorageUncertain(),
                ) { "Cancelled harness write retained recovery evidence (${error::class.simpleName.orEmpty()})" }
            }
        }

    private suspend fun cleanCommitted(prepared: HarnessPendingCommit) {
        try {
            clear()
            images.clean(prepared)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(
                HarnessStorageUncertain(),
            ) { "Committed harness journal cleanup deferred (${error::class.simpleName.orEmpty()})" }
        }
    }

    private suspend fun clear() {
        store.remove(key)
        if (store.get(key) != null) throw HarnessStorageUncertain()
    }

    private suspend fun readPending(): HarnessPendingCommit? {
        val raw = store.get(key) ?: return null
        return try {
            Json.decodeFromString<HarnessPendingCommit>(raw)
        } catch (error: IllegalArgumentException) {
            throw error.withoutHarnessRecordText()
        }
    }

    private companion object {
        const val CANCELLATION_CLEANUP_MILLIS = 5_000L
    }
}

private fun uncertainJournalOutcome(): Nothing = throw HarnessStorageUncertain()

private fun confirmedRollbackFailure(): Nothing = throw HarnessStorageFailure()
