package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
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

/** Verifies a wake host and confirms the exact request only after its state has reached durable storage. */
@Inject
internal class WakeScheduler(
    private val machine: SchedulerMachine,
    // Lazy: hosts depend on the runtime, which itself uses the hosted tools.
    private val hosts: Lazy<Set<ScheduledSessionHost>>,
    private val persistence: SchedulerPersistence,
) {
    /** Schedules [request] at [now]. */
    suspend fun schedule(request: WakeRequest, now: Instant): ScheduleOutcome = coroutineScope {
        if (hosts.value.none { it.owns(request.session) }) return@coroutineScope ScheduleOutcome.NoHost
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.mapNotNull { it.outcomeOf(request.id) }.first()
        }
        val sent = machine.send(SchedulerIntent.Public.Schedule(request, now))
        val outcome = if (sent == SendResult.Accepted) {
            withTimeoutOrNull(ANSWER_TIMEOUT) { confirm(answer.await()) } ?: ScheduleOutcome.Unconfirmed
        } else {
            ScheduleOutcome.NotTaken
        }
        answer.cancel()
        outcome
    }

    private suspend fun confirm(outcome: ScheduleOutcome): ScheduleOutcome {
        if (outcome == ScheduleOutcome.Scheduled) {
            // State is published before outputs. A newer snapshot also includes this request or its settlement.
            val revision = (machine.state.value as? SchedulerState.Ready)?.revision
                ?: return ScheduleOutcome.Unconfirmed
            persistence.revision.first { it >= revision }
        }
        return outcome
    }
}

/** A fresh wake id. */
internal fun newWakeId(): WakeId = WakeId("w" + Uuid.random().toHexString().take(ID_LENGTH))

/** A fresh id of a background action. */
internal fun newActionId(): ActionId = ActionId("a" + Uuid.random().toHexString().take(ID_LENGTH))

/** What became of a wake request. */
internal sealed interface ScheduleOutcome {
    /** The wake was accepted and its state is durable across profile reopening. */
    data object Scheduled : ScheduleOutcome

    /** The machine refused it. */
    data class Rejected(val rejection: WakeRejection) : ScheduleOutcome

    /** No host can submit a continuation while showing tools and servicing permissions. */
    data object NoHost : ScheduleOutcome

    /** The machine did not take the request (still loading). */
    data object NotTaken : ScheduleOutcome

    /** The machine took the request but its answer or durable write was not confirmed in time. */
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

    ScheduleOutcome.NoHost ->
        "this session does not support scheduled wake-ups; no wake was scheduled. " +
            "Continue the task in this turn; do not end it expecting a wake-up. " +
            "For run_build, wait with build_status and its operation id. " +
            "In organic AI, use organism_receive with wait=true to wait for cell results"

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

    is SchedulerOutput.Cancelled,
    is SchedulerOutput.Woke,
    is SchedulerOutput.Deferred,
    is SchedulerOutput.DeliveryFailed,
    -> null
}
