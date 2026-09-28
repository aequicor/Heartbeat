package io.aequicor.heartbeat.feature.questionnaire.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
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
 * only while the questionnaire is enabled, it asks the saved questions again and only then saves the queue on every
 * change: [Machine.send] returns after the transition, so the mirrored queue already holds every restored question.
 * Answers in flight (`submitting`) are not saved, so an answered question is never asked twice after a restart.
 * Storage failures are logged and never stop the journal.
 */
class QuestionnaireJournal(private val storage: QuestionnaireStorage) {
    private val log = Log.tag("QuestionnaireJournal")

    /** Restores and then mirrors [machine] until cancelled. */
    suspend fun run(machine: Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput>) {
        val saved = load()
        log.i { "Restore open questions count=${saved.size}" }
        saved.forEach { question ->
            val result = machine.send(QuestionnaireIntent.Public.Ask(question))
            if (result != SendResult.Accepted) log.w { "Saved question was not asked again: $result" }
        }
        var stored = saved
        machine.state
            .map { state ->
                val asking = state as? QuestionnaireState.Asking
                asking?.pending.orEmpty().filterNot { it.id in asking?.submitting.orEmpty() }
            }
            .distinctUntilChanged()
            // Writes only real changes: opening a profile with nothing new to save touches no storage.
            .filter { it != stored }
            .collect { pending ->
                if (save(pending)) stored = pending
            }
    }

    private suspend fun load(): List<Questionnaire> = try {
        storage.load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Failed to load open questions" }
        emptyList()
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
