package io.aequicor.heartbeat.feature.browser.impl.domain

internal actual fun Throwable.redactedBrowserFailure(): Throwable =
    IllegalStateException(javaClass.name).also { it.stackTrace = stackTrace }
