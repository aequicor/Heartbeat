package io.aequicor.heartbeat.feature.feedback.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.feedback.api.FeedbackIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutput
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import io.aequicor.heartbeat.feature.feedback.api.FeedbackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Profile-owned durable copy of the reporting machine, independent of native session history. */
internal interface FeedbackStorage {
    /** Reads the ordered history; a read failure must never be mistaken for an empty history. */
    suspend fun load(): List<FeedbackRecord>

    /** Atomically replaces the last successfully loaded snapshot. */
    suspend fun save(records: List<FeedbackRecord>)
}

/**
 * Restores and sequentially mirrors the profile feedback machine. New reports received while loading are merged
 * by the machine before saving begins. Failed initial reads prevent writes until a later state change recovers
 * the stored history, so unknown durable records are never replaced by an empty or partial snapshot.
 * Save failures leave live reports available and the next changed snapshot retries persistence.
 */
internal class FeedbackJournal(private val storage: FeedbackStorage) {
    private val log = Log.tag("FeedbackJournal")

    /** Runs until the profile closes; cancelling a caller never replays a configuration operation. */
    suspend fun run(machine: Machine<FeedbackState, FeedbackIntent, FeedbackOutput>) {
        var stored = load()
        val restoration = stored?.let { FeedbackIntent.Internal.Loaded(it) } ?: FeedbackIntent.Internal.LoadFailed
        val restored = machine.send(restoration)
        if (restored != SendResult.Accepted) {
            log.w { "Feedback journal restoration rejected: $restored" }
            return
        }
        machine.state.filterIsInstance<FeedbackState.Ready>()
            .map { it.records }
            .distinctUntilChanged()
            .collect { snapshot ->
                var next = snapshot
                if (stored == null) {
                    // No write is safe until the previously stored history has been read successfully.
                    val recovered = load() ?: return@collect
                    val result = machine.send(FeedbackIntent.Internal.Loaded(recovered))
                    if (result != SendResult.Accepted) {
                        log.w { "Feedback journal recovery rejected: $result" }
                        return@collect
                    }
                    stored = recovered
                    next = (machine.state.value as? FeedbackState.Ready)?.records ?: return@collect
                }
                if (next != stored && save(next)) {
                    stored = next
                } else if (next != stored) {
                    machine.send(FeedbackIntent.Internal.SaveFailed)
                }
            }
    }

    private suspend fun load(): List<FeedbackRecord>? = try {
        storage.load().also { log.d { "Restore feedback records count=${it.size}" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Could not read feedback history" }
        null
    }

    private suspend fun save(records: List<FeedbackRecord>): Boolean = try {
        log.d { "Save feedback records count=${records.size}" }
        storage.save(records)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Could not save feedback history" }
        false
    }
}
