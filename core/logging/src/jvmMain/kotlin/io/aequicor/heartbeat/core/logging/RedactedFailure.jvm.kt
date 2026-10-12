package io.aequicor.heartbeat.core.logging

/** Copies the exception type and stack frames; message, causes and suppressed errors are dropped. */
public actual fun Throwable.redactedFailure(): Throwable =
    IllegalStateException(javaClass.name).also { it.stackTrace = stackTrace }
