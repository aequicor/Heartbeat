package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserNativeDeadlineTest {
    @Test
    fun `native cleanup cannot keep the test worker waiting forever`() {
        val closed = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val deadline = BrowserNativeDeadline(timeoutMillis = 100)
        deadline.stage("favicon cleanup")
        val statement = object : Statement() {
            override fun evaluate() {
                try {
                    runBlocking { withContext(NonCancellable) { closed.await() } }
                } finally {
                    exited.complete(Unit)
                }
            }
        }
        try {
            val failure = assertFailsWith<TestTimedOutException> {
                deadline.apply(statement, description()).evaluate()
            }
            assertTrue(failure.suppressed.any { it.message == "Native browser stage: favicon cleanup" })
        } finally {
            closed.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(5_000) { exited.await() } }
        }
    }

    @Test
    fun `a failed probe retains its original failure and stage`() {
        val original = AssertionError("missing icon")
        val deadline = BrowserNativeDeadline()
        deadline.stage("favicon CDP and transfer")
        val statement = object : Statement() {
            override fun evaluate() = throw original
        }
        val failure = assertFailsWith<AssertionError> { deadline.apply(statement, description()).evaluate() }
        assertSame(original, failure)
        assertEquals("Native browser stage: favicon CDP and transfer", failure.suppressed.single().message)
    }

    private fun description(): Description = Description.createTestDescription(javaClass, "native fixture")
}
