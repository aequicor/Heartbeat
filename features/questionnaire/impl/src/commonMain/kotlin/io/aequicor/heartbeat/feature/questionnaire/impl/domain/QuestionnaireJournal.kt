package io.aequicor.heartbeat.feature.questionnaire.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
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
 * Keeps open questions across screens and app restarts: asks the saved questions again when the profile queue
 * starts, then saves the queue on every change. Answers in flight are not saved; their questions reopen.
 */
class QuestionnaireJournal(private val storage: QuestionnaireStorage) {
    private val log = Log.tag("QuestionnaireJournal")

    /** Restores and then mirrors [machine] until cancelled. */
    suspend fun run(machine: Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput>) {
        val saved = storage.load()
        log.i { "Restore open questions count=${saved.size}" }
        saved.forEach { machine.send(QuestionnaireIntent.Public.Ask(it)) }
        var stored = saved
        machine.state
            .map { (it as? QuestionnaireState.Asking)?.pending.orEmpty() }
            .distinctUntilChanged()
            // Writes only real changes: opening a profile with nothing new to save touches no storage.
            .filter { it != stored }
            .collect { pending ->
                log.d { "Save open questions count=${pending.size}" }
                storage.save(pending)
                stored = pending
            }
    }
}
