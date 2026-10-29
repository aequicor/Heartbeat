package io.aequicor.heartbeat.feature.questionnaire.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.questionnaire.api.LIVE_QUESTION_ID_PREFIX
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Durable copy of the profile question queue. */
interface QuestionnaireStorage {
    /** Questions that were open when the queue was last saved. */
    suspend fun load(): List<Questionnaire>

    /** Replaces the saved questions. */
    suspend fun save(pending: List<Questionnaire>)
}

/**
 * Keeps open questions across screens and app restarts (the profile machine itself is not persisted). Started
 * only while the questionnaire is enabled, it atomically restores only ids untouched by this machine and then
 * saves the queue on every change. [Machine.send] returns after the transition, so the mirrored queue already
 * holds every restored question. Exact touched-id exclusions survive generation-history eviction: an ignored
 * Restore therefore means the live machine already owns the decision for that id, never an ambiguous overflow.
 * Answers in flight (`submitting`) are not saved, so an answered question is never asked twice after a restart.
 * Questions of live callers (`LIVE_QUESTION_ID_PREFIX`) stay out of storage: their owner delivers answers within its
 * own turn and is gone after a restart.
 * After a complete successful load/restore, FinishInitialRestore atomically seals restoration and releases exact
 * exclusions. Reopening or overlapping loads cannot restore stale records after this seal. A load failure,
 * cancellation or NotRunning aborts this run without sealing or saving a partial queue over durable entries.
 * Save failures are logged; later queue changes retry saving.
 *
 * The owner must serialize journal writers, including completion of cancelled saves. Storage.save has no revision
 * or compare-and-set contract: independent overlapping writers can finish stale writes last. The restore seal
 * protects the machine queue, not the ordering or cancellation semantics of external storage writes.
 */
class QuestionnaireJournal(private val storage: QuestionnaireStorage) {
    private val log = Log.tag("QuestionnaireJournal")

    /** Restores and then mirrors [machine] until cancelled. */
    suspend fun run(machine: Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput>) {
        val saved = load() ?: return
        log.i { "Restore open questions count=${saved.size}" }
        saved.forEach { question ->
            val result = machine.send(QuestionnaireIntent.Public.Restore(question))
            if (result != SendResult.Accepted) log.w { "Saved question was not asked again: $result" }
            if (result == SendResult.NotRunning) return
        }
        currentCoroutineContext().ensureActive()
        val sealed = machine.send(QuestionnaireIntent.Public.FinishInitialRestore)
        if (sealed == SendResult.NotRunning ||
            (machine.state.value as? QuestionnaireState.Asking)?.publications?.isInitialRestoreFinished != true
        ) {
            log.w { "Initial restore was not sealed: $sealed" }
            return
        }
        currentCoroutineContext().ensureActive()
        var stored = saved
        machine.state
            .map { state ->
                val asking = state as? QuestionnaireState.Asking ?: return@map emptyList()
                asking.pending
                    .filterNot { it.id in asking.submitting }
                    .filterNot { it.id.value.startsWith(LIVE_QUESTION_ID_PREFIX) }
            }
            .distinctUntilChanged()
            // Writes only real changes: opening a profile with nothing new to save touches no storage.
            .filter { it != stored }
            .collect { pending ->
                if (save(pending)) stored = pending
            }
    }

    private suspend fun load(): List<Questionnaire>? = try {
        storage.load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Failed to load open questions" }
        null
    }

    private suspend fun save(pending: List<Questionnaire>): Boolean = try {
        log.d { "Save open questions count=${pending.size}" }
        storage.save(pending)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Failed to save open questions count=${pending.size}" }
        false
    }
}
