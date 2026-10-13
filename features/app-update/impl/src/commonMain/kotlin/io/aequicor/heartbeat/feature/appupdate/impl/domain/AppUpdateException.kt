package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure

/** An update step failed for a reason the user sees as [failure]. */
internal class AppUpdateException(val failure: AppUpdateFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/** Stops the current update step with [failure]. */
internal fun updateFailure(failure: AppUpdateFailure, cause: Throwable? = null): Nothing =
    throw AppUpdateException(failure, cause)

/**
 * This failure without its message: file system and process messages name the user's paths, which are never logged.
 * Only the kind of failure is kept.
 */
internal fun Throwable.withoutDetails(): Throwable = IllegalStateException(this::class.simpleName ?: "Failure")
