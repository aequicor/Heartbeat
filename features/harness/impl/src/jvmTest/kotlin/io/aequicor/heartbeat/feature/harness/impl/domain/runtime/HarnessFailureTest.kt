package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

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
    fun `successful values remain usable without appearing in diagnostics`() = runTest {
        val result = assertIs<HarnessAttempt.Success<String>>(captureHarnessFailure { "private result" })
        assertEquals("private result", result.value)
        assertEquals("HarnessAttempt.Success(***)", result.toString())
    }
}
