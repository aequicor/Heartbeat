package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

class ModelCheckProbesTest {
    @Test
    fun `a different probe definition cannot be labelled with results of the current probes`() = runTest {
        val probes = ModelCheckProbes(IsolatedPrompts { error("Mismatched probe definition") })
        assertNull(
            probes.run(
                checkReport().revision.copy(definition = ModelCheckRevision.CURRENT_DEFINITION + 1),
                checkReport().run,
                TestTimeSource().markNow() + 5.seconds,
            ) { true },
        )
    }

    @Test
    fun `two fresh bounded requests use only the remainder of the shared deadline`() = runTest {
        val time = TestTimeSource()
        val deadline = time.markNow() + 5.seconds
        time += 2.seconds // Saving other selected models already used part of the batch budget.
        val requests = mutableListOf<IsolatedPrompt>()
        val fixedTime = Instant.fromEpochSeconds(42)
        val probes = ModelCheckProbes(
            IsolatedPrompts {
                requests += it
                time += 1.seconds
                IsolatedAnswer.Answered(if (requests.size == 1) "READY" else "19|cedar|ABSENT")
            },
            object : Clock {
                override fun now(): Instant = fixedTime
            },
        )
        val fixture = checkReport()
        val result = assertNotNull(probes.run(fixture.revision, fixture.run, deadline) { true })
        assertFalse(result.hasWarning)
        assertEquals(fixedTime, result.checkedAt)
        assertEquals(listOf(3.seconds, 2.seconds), requests.map { it.timeout })
        assertEquals(
            listOf(ModelCheckEvaluation.RESPONSE_PROBE, ModelCheckEvaluation.ACCURACY_PROBE),
            requests.map { it.text },
        )
        requests.forEach {
            assertEquals(fixture.revision.target, it.target)
            val constraints = assertNotNull(it.constraints)
            assertEquals(fixture.revision.source, constraints.expectedSource)
            assertEquals(128, constraints.maxOutputTokens)
            assertEquals(1025, it.maxAnswerChars)
        }
    }

    @Test
    fun `an expired batch records two incomplete aspects without sending anything`() = runTest {
        val time = TestTimeSource()
        val deadline = time.markNow() + 5.seconds
        time += 5.seconds
        val result = assertNotNull(
            ModelCheckProbes(IsolatedPrompts { error("Expired check must not dispatch") }).run(
                checkReport().revision,
                checkReport().run,
                deadline,
            ) { true },
        )
        assertEquals(incomplete(ModelCheckIncomplete.Timeout), result.response.outcome)
        assertEquals(incomplete(ModelCheckIncomplete.Timeout), result.accuracy.outcome)
    }

    @Test
    fun `a response that consumes the deadline cannot pass or start the second request`() = runTest {
        val time = TestTimeSource()
        var calls = 0
        val probes = ModelCheckProbes(
            IsolatedPrompts {
                calls++
                time += 5.seconds
                IsolatedAnswer.Answered("READY")
            },
        )
        val result = assertNotNull(
            probes.run(checkReport().revision, checkReport().run, time.markNow() + 5.seconds) { true },
        )
        assertEquals(1, calls)
        assertEquals(incomplete(ModelCheckIncomplete.Timeout), result.response.outcome)
        assertEquals(incomplete(ModelCheckIncomplete.Timeout), result.accuracy.outcome)
        assertNull(result.response.answer)
    }

    @Test
    fun `all strict refusal values remain incomplete and both aspects are independent`() = runTest {
        val reasons = mapOf(
            IsolatedPromptFailure.Unsupported to ModelCheckIncomplete.IsolationUnproven,
            IsolatedPromptFailure.IsolationUnproven to ModelCheckIncomplete.IsolationUnproven,
            IsolatedPromptFailure.TokenLimitUnproven to ModelCheckIncomplete.TokenLimitUnproven,
            IsolatedPromptFailure.SourceChanged to ModelCheckIncomplete.RouteChanged,
            IsolatedPromptFailure.ResponseTooLarge to ModelCheckIncomplete.ResponseTooLarge,
            IsolatedPromptFailure.Busy to ModelCheckIncomplete.Busy,
            IsolatedPromptFailure.Closing to ModelCheckIncomplete.Unavailable,
            IsolatedPromptFailure.Timeout to ModelCheckIncomplete.Timeout,
            IsolatedPromptFailure.Incomplete to ModelCheckIncomplete.Unavailable,
            IsolatedPromptFailure.Failed to ModelCheckIncomplete.Unavailable,
        )
        assertEquals(IsolatedPromptFailure.entries.toSet(), reasons.keys)
        reasons.forEach { (failure, expected) ->
            var calls = 0
            val probes = ModelCheckProbes(
                IsolatedPrompts {
                    calls++
                    if (calls == 1) IsolatedAnswer.Unavailable(failure) else IsolatedAnswer.Answered("19|cedar|ABSENT")
                },
            )
            val result = assertNotNull(
                probes.run(checkReport().revision, checkReport().run, TestTimeSource().markNow() + 5.seconds) { true },
            )
            assertEquals(incomplete(expected), result.response.outcome)
            assertEquals(ModelCheckOutcome.Passed, result.accuracy.outcome)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `invalidated identity before and during either request discards the entire run`() = runTest {
        for (invalidatedAt in 0..2) {
            var calls = 0
            var active = invalidatedAt != 0
            val probes = ModelCheckProbes(
                IsolatedPrompts {
                    calls++
                    if (calls == invalidatedAt) active = false
                    IsolatedAnswer.Answered("discarded answer")
                },
            )
            val result = probes.run(checkReport().revision, checkReport().run, TestTimeSource().markNow() + 5.seconds) {
                active
            }
            assertNull(result)
            assertEquals(invalidatedAt, calls)
        }
    }

    @Test
    fun `identity change immediately before final publication discards two completed answers`() = runTest {
        var validations = 0
        var calls = 0
        val probes = ModelCheckProbes(
            IsolatedPrompts {
                calls++
                IsolatedAnswer.Answered(if (calls == 1) "READY" else "19|cedar|ABSENT")
            },
        )
        val result = probes.run(checkReport().revision, checkReport().run, TestTimeSource().markNow() + 5.seconds) {
            ++validations < 6
        }
        assertEquals(2, calls)
        assertEquals(6, validations)
        assertNull(result)
    }

    @Test
    fun `a slow identity check consumes the deadline before any request`() = runTest {
        val time = TestTimeSource()
        val result = assertNotNull(
            ModelCheckProbes(IsolatedPrompts { error("Identity validation exhausted the budget") }).run(
                checkReport().revision,
                checkReport().run,
                time.markNow() + 5.seconds,
            ) {
                time += 3.seconds
                true
            },
        )
        assertEquals(incomplete(ModelCheckIncomplete.Timeout), result.response.outcome)
        assertEquals(incomplete(ModelCheckIncomplete.Timeout), result.accuracy.outcome)
    }

    @Test
    fun `cancellation propagates and never sends a second probe`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        val probes = ModelCheckProbes(
            IsolatedPrompts {
                calls++
                entered.complete(Unit)
                try {
                    CompletableDeferred<IsolatedAnswer>().await()
                } catch (error: CancellationException) {
                    cancelled = true
                    throw error
                }
            },
        )
        val task = async {
            probes.run(checkReport().revision, checkReport().run, TestTimeSource().markNow() + 5.seconds) { true }
        }
        entered.await()
        task.cancelAndJoin()
        assertEquals(1, calls)
        assertTrue(cancelled)
        assertTrue(task.isCancelled)
    }

    @Test
    fun `provider exceptions produce no raw answer and do not suppress the independent accuracy probe`() = runTest {
        var calls = 0
        val probes = ModelCheckProbes(
            IsolatedPrompts {
                if (++calls == 1) {
                    error("provider-payload-secret-canary")
                } else {
                    IsolatedAnswer.Answered("19|cedar|ABSENT")
                }
            },
        )
        val result = assertNotNull(
            probes.run(checkReport().revision, checkReport().run, TestTimeSource().markNow() + 5.seconds) { true },
        )
        assertEquals(incomplete(ModelCheckIncomplete.Unavailable), result.response.outcome)
        assertNull(result.response.answer)
        assertEquals(ModelCheckOutcome.Passed, result.accuracy.outcome)
        assertFalse(result.toString().contains("canary"))
    }

    @Test
    fun `oversized complete text cannot silently pass through a facade that ignores the budget`() = runTest {
        val probes = ModelCheckProbes(IsolatedPrompts { IsolatedAnswer.Answered("a".repeat(1025)) })
        val result = assertNotNull(
            probes.run(checkReport().revision, checkReport().run, TestTimeSource().markNow() + 5.seconds) { true },
        )
        assertEquals(incomplete(ModelCheckIncomplete.ResponseTooLarge), result.response.outcome)
        assertEquals(incomplete(ModelCheckIncomplete.ResponseTooLarge), result.accuracy.outcome)
        assertEquals(1024, result.response.answer?.length)
    }
}

private fun incomplete(reason: ModelCheckIncomplete): ModelCheckOutcome = ModelCheckOutcome.Incomplete(reason)
