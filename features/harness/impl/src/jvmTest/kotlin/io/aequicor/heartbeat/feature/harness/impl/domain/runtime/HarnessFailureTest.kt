package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HarnessFailureTest {
    @Test
    fun `documented user errors become safe failures without retaining private diagnostics`() = runTest {
        val errors = listOf(
            IllegalStateException("private source"),
            NotImplementedError("private source"),
            StackOverflowError("private source"),
            LinkageError("private source"),
            AssertionError("private source"),
            ExceptionInInitializerError(IllegalArgumentException("private source")),
        )
        for (error in errors) {
            val failure = assertIs<HarnessAttempt.Failure>(captureHarnessFailure<Nothing> { throw error })
            assertEquals("Harness script failed", failure.error.message)
            assertNull(failure.error.cause)
            assertEquals(0, failure.error.suppressed.size)
        }
    }

    @Test
    fun `cancellation and fatal errors propagate unchanged including wrapped causes`() = runTest {
        val cancellation = CancellationException("cancelled")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                captureHarnessFailure<Nothing> { throw cancellation }
            },
        )
        val fatal = OutOfMemoryError("private source")
        assertSame(
            fatal,
            assertFailsWith<OutOfMemoryError> {
                captureHarnessFailure<Nothing> { throw fatal }
            },
        )
        assertSame(
            fatal,
            assertFailsWith<OutOfMemoryError> {
                captureHarnessFailure<Nothing> { throw ExceptionInInitializerError(fatal) }
            },
        )
        val unknown = object : Error("unknown platform failure") {}
        assertSame(
            unknown,
            assertFailsWith<Error> {
                captureHarnessFailure<Nothing> { throw unknown }
            },
        )
    }

    @Test
    fun `cancellation nested in a script wrapper is a sanitized failure, not a cancellation`() = runTest {
        val errors = listOf(
            IllegalStateException("private slow call", CancellationException("private timeout")),
            CompletionException(CancellationException("private background cancellation")),
        )
        for (error in errors) {
            assertIs<ScriptFailure>(harnessScriptFailure(error))
            val failure = assertIs<HarnessAttempt.Failure>(captureHarnessFailure<Nothing> { throw error })
            assertNull(failure.error.cause)
        }
        val fatal = OutOfMemoryError("private source")
        assertSame(
            fatal,
            assertFailsWith<OutOfMemoryError> {
                harnessScriptFailure(IllegalStateException(CancellationException("nested").apply { initCause(fatal) }))
            },
        )
    }

    @Test
    fun `host limit refusals are marked even when wrapped, while fatal causes still propagate`() = runTest {
        val refusal = HarnessLimitRefusal("streak")
        for (error in listOf(refusal, IllegalStateException("private wrapper", refusal))) {
            val failure = assertIs<HarnessAttempt.Failure>(captureHarnessFailure<Nothing> { throw error })
            assertTrue(failure.error.isRefusal)
            assertNull(failure.error.cause)
        }
        val ordinary = captureHarnessFailure<Nothing> { throw IllegalStateException() }
        assertFalse(assertIs<HarnessAttempt.Failure>(ordinary).error.isRefusal)
        val fatal = OutOfMemoryError("private source")
        assertSame(
            fatal,
            assertFailsWith<OutOfMemoryError> {
                captureHarnessFailure<Nothing> { throw HarnessLimitRefusal("streak").apply { initCause(fatal) } }
            },
        )
    }

    @Test
    fun `successful values remain usable without appearing in diagnostics`() = runTest {
        val result = assertIs<HarnessAttempt.Success<String>>(captureHarnessFailure { "private result" })
        assertEquals("private result", result.value)
        assertEquals("HarnessAttempt.Success(***)", result.toString())
    }
}
