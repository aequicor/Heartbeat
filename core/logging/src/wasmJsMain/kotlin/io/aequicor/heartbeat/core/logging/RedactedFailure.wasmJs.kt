package io.aequicor.heartbeat.core.logging

/** Kotlin/Wasm cannot transfer stack frames to another throwable, so only the type is kept. */
public actual fun Throwable.redactedFailure(): Throwable =
    IllegalStateException(this::class.qualifiedName ?: "Throwable")
