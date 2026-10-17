package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import kotlinx.coroutines.CancellationException

private val log = Log.tag("WorktreePlanRejection")
private val rejectionCode = Regex("[A-Za-z0-9]+")

/**
 * The rejection code of [plan] for this chat's checkout, or null when it is acceptable; nothing is stored. The
 * machine rejects an invalid plan too, but an identical repeated failure changes no state, so without this check
 * the agent would only learn about it from a timeout.
 */
internal suspend fun WorktreeJournal.planRejection(chatId: String, plan: WorktreeBuildPlan): String? {
    val existing = record(chatId) ?: return null
    return try {
        git.validatePlan(existing, plan)
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        val code = plainCode(error, "InvalidBuildPlan")
        log.w(error.redactedFailure()) { "Build plan rejected code=$code" }
        code
    } catch (error: Exception) {
        // The machine answers the same way for a failure of the checkout itself (WorktreeUnavailable and the like).
        val code = plainCode(error, "OperationFailed")
        log.w(error.redactedFailure()) { "Build plan rejected code=$code" }
        code
    }
}

/** Only plain codes reach the agent: other messages may contain filesystem text. */
private fun plainCode(error: Exception, fallback: String): String =
    error.message?.takeIf { it.matches(rejectionCode) } ?: fallback
