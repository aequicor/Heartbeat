package io.aequicor.heartbeat.feature.questionnaire.impl.di

import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.impl.domain.QuestionnaireStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuestionnaireStartupTest {
    private val storage = object : QuestionnaireStorage {
        override suspend fun load(): List<Questionnaire> = emptyList()
        override suspend fun save(pending: List<Questionnaire>) = Unit
    }

    @Test
    fun `transient initialization failure retries without abandoning the journal`() = runTest {
        var attempts = 0
        val dependency = lazy {
            attempts += 1
            check(attempts > 1) { "Storage temporarily unavailable" }
            storage
        }
        val journal = async { awaitQuestionnaireJournal(dependency) }
        runCurrent()
        assertEquals(1, attempts)
        assertFalse(journal.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, attempts)
        assertTrue(journal.isCompleted)
        journal.await()
    }

    @Test
    fun `cancellation during initialization does not restart storage`() = runTest {
        var attempts = 0
        val dependency = lazy<QuestionnaireStorage> {
            attempts += 1
            throw CancellationException("Profile closed")
        }
        val journal = async { awaitQuestionnaireJournal(dependency) }
        runCurrent()
        assertTrue(journal.isCancelled)
        advanceTimeBy(60_000)
        assertEquals(1, attempts)
    }
}
