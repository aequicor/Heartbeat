package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome

private val log = Log.tag("NativeRunOutcomes")

/**
 * Maps the outcome an engine reported onto the studio run outcome. An engine that cannot confirm a remote
 * cancellation (Koog only closes its stream) settles a stopped turn as Unknown; after the user's explicit stop that is
 * the stop, not a failure to report. [onFailure] runs for every other result that is not a success or a stop.
 */
internal suspend fun nativeRunOutcome(
    outcome: TurnOutcome?,
    isStopRequested: Boolean,
    onFailure: suspend () -> Unit,
): RunOutcome = when {
    outcome == TurnOutcome.Unknown && isStopRequested -> {
        log.d { "Unconfirmed outcome after an explicit stop counts as stopped" }
        RunOutcome.Stopped
    }

    else -> when (outcome) {
        TurnOutcome.Completed -> RunOutcome.Completed

        TurnOutcome.Cancelled -> RunOutcome.Stopped

        TurnOutcome.Unknown, null, is TurnOutcome.Failed -> {
            log.w {
                "Native turn did not complete successfully type=${outcome?.let { it::class.simpleName }.orEmpty()} " +
                    "code=${(outcome as? TurnOutcome.Failed)?.failure?.code.orEmpty()}"
            }
            onFailure()
            RunOutcome.Failed
        }
    }
}
