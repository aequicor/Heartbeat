package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ANSWER_TIMEOUT = 10.seconds
private const val ID_LENGTH = 12

/** Schedules wakes for hosted tools and waits for the machine's answer to the exact request. */
@Inject
internal class WakeScheduler(private val machine: SchedulerMachine) {
    /** Schedules [request] at [now]. */
    suspend fun schedule(request: WakeRequest, now: Instant): ScheduleOutcome = coroutineScope {
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.mapNotNull { it.outcomeOf(request.id) }.first()
        }
        val sent = machine.send(SchedulerIntent.Public.Schedule(request, now))
        val outcome = if (sent == SendResult.Accepted) {
            withTimeoutOrNull(ANSWER_TIMEOUT) { answer.await() } ?: ScheduleOutcome.Unconfirmed
        } else {
            ScheduleOutcome.NotTaken
        }
        answer.cancel()
        outcome
    }
}

/** A fresh wake id. */
internal fun newWakeId(): WakeId = WakeId("w" + Uuid.random().toHexString().take(ID_LENGTH))

/** A fresh id of a background action. */
internal fun newActionId(): ActionId = ActionId("a" + Uuid.random().toHexString().take(ID_LENGTH))

/** What became of a wake request. */
internal sealed interface ScheduleOutcome {
    /** The wake is pending. */
    data object Scheduled : ScheduleOutcome

    /** The machine refused it. */
    data class Rejected(val rejection: WakeRejection) : ScheduleOutcome

    /** The machine did not take the request (still loading). */
    data object NotTaken : ScheduleOutcome

    /** The machine took the request but did not answer in time. */
    data object Unconfirmed : ScheduleOutcome
}

/** The tool message for an outcome other than [ScheduleOutcome.Scheduled]. */
internal fun ScheduleOutcome.failureMessage(): String = when (this) {
    ScheduleOutcome.Scheduled -> "scheduled"

    is ScheduleOutcome.Rejected -> when (rejection) {
        WakeRejection.Duplicate -> "a wake with this id already exists"
        WakeRejection.SessionLimit -> "this session already has ${SchedulerLimits.MAX_PER_SESSION} pending wakes"
        WakeRejection.ProfileLimit -> "the profile already has ${SchedulerLimits.MAX_PER_PROFILE} pending wakes"
        WakeRejection.TooFar -> "the deadline is further than ${SchedulerLimits.HORIZON} from now"
    }

    ScheduleOutcome.NotTaken -> "the scheduler is still starting; try again in a moment"

    ScheduleOutcome.Unconfirmed -> "the wake is not confirmed yet; check it with the list tool"
}

/** What the agent is told it waits for. */
internal fun WakeCondition.describe(): String = listOfNotNull(
    events.takeIf { it.isNotEmpty() }?.joinToString(prefix = "one of [", postfix = "] arrives"),
    deadline?.let { "the time is $it" },
).joinToString(" or ")

private fun SchedulerOutput.outcomeOf(id: WakeId): ScheduleOutcome? = when (this) {
    is SchedulerOutput.Scheduled -> ScheduleOutcome.Scheduled.takeIf { wake.id == id }
    is SchedulerOutput.Rejected -> ScheduleOutcome.Rejected(rejection).takeIf { this.id == id }
    is SchedulerOutput.Cancelled, is SchedulerOutput.Woke, is SchedulerOutput.DeliveryFailed -> null
}
