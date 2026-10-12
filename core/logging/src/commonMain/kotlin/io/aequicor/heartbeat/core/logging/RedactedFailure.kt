package io.aequicor.heartbeat.core.logging

/**
 * Loggable copy of a failure whose message may carry private data (URLs, page titles, paths, user text): keeps the
 * exception type and, where the platform allows, its stack frames, but drops the message, causes and suppressed
 * errors. Pass the result as the throwable of `log.w` / `log.e` instead of an exception built from the type name.
 */
public expect fun Throwable.redactedFailure(): Throwable
