package io.aequicor.heartbeat.feature.browser.impl.ui

import java.util.concurrent.ConcurrentLinkedQueue

/** Isolated native-test JVM only: retains complete asynchronous failures through native shutdown. */
internal class NativeUncaughtFailures : AutoCloseable {
    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val handler = Thread.UncaughtExceptionHandler { thread, error ->
        failures += AssertionError("Uncaught native test failure on ${thread.name}", error)
    }

    init {
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    override fun close() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val first = failures.poll() ?: return
        failures.forEach(first::addSuppressed)
        throw first
    }
}
