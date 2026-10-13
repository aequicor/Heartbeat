package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure

/**
 * An update step failed for a reason the user sees as [failure]; [detail] tells the log why, without paths. The
 * effect handler logs it once.
 */
internal class AppUpdateException(val failure: AppUpdateFailure, detail: String? = null, cause: Throwable? = null) :
    Exception(detail?.let { "$failure: $it" } ?: failure.name, cause)

/** Stops the current update step with [failure]; [detail] is logged with it. */
internal fun updateFailure(failure: AppUpdateFailure, detail: String? = null, cause: Throwable? = null): Nothing =
    throw AppUpdateException(failure, detail, cause)
