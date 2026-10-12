package io.aequicor.heartbeat.feature.browser.impl.domain

/** Kotlin/Native cannot transfer stack frames to another throwable, so only the type is kept. */
internal actual fun Throwable.redactedBrowserFailure(): Throwable =
    IllegalStateException(this::class.qualifiedName ?: "Throwable")
