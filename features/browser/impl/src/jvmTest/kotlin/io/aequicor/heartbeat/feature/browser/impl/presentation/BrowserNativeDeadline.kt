package io.aequicor.heartbeat.feature.browser.impl.presentation

import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException
import java.util.concurrent.TimeUnit

/**
 * A wall-clock deadline, including JNI calls and NonCancellable native cleanup. Coroutine timeouts alone cannot
 * stop those waits. JUnit interrupts the daemon test thread and reports its stack without waiting for it to exit.
 */
internal class BrowserNativeDeadline(private val timeoutMillis: Long = 300_000) : TestRule {
    @Volatile
    private var currentStage = "initialization"

    fun stage(name: String) {
        currentStage = name
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                Timeout.builder()
                    .withTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
                    .apply(base, description)
                    .evaluate()
            } catch (failure: TestTimedOutException) {
                throw AssertionError("Native browser timed out in stage: $currentStage", failure)
            } catch (failure: Throwable) {
                failure.addSuppressed(AssertionError("Native browser stage: $currentStage"))
                throw failure
            }
        }
    }
}
