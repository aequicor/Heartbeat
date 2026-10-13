package io.aequicor.heartbeat.feature.appupdate.impl.data

/**
 * This failure without its message: file system and process messages name the user's paths, which are never logged.
 * The kind of failure and the stack trace are kept.
 */
internal fun Throwable.withoutDetails(): Throwable =
    IllegalStateException(this::class.simpleName ?: "Failure").also { it.stackTrace = stackTrace }
