package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools.Arguments
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The agent's side of the scheduler: sleep until an event or a deadline, publish a named signal, cancel and list own
 * wakes. Every finished turn publishes the session's `turn_finished` key, so other sessions can wait for it. All tools
 * address only the calling session, never decoded from arguments. Notes and payloads are never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class SchedulerAgentTools(
    private val machine: SchedulerMachine,
    private val scheduler: WakeScheduler,
    private val bus: SchedulerBus,
    private val toggles: FeatureToggles,
    private val clock: Clock,
    private val network: NetworkStatus,
) : AgentToolContribution {
    private val log = Log.tag("SchedulerAgentTools")

    override val isDetachedSupported: Boolean get() = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(SchedulerEnabled)) listOf(SLEEP_SPEC, SIGNAL_SPEC, CANCEL_SPEC, LIST_SPEC) else emptyList()

    override suspend fun instructions(scope: AgentToolScope): String =
        if (toggles.get(SchedulerEnabled)) INSTRUCTIONS else ""

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(SchedulerEnabled)) return failure("the scheduler is turned off")
        return when (name) {
            SchedulerTools.SLEEP -> sleep(context, arguments)
            SchedulerTools.SIGNAL -> signal(context, arguments)
            SchedulerTools.CANCEL -> cancel(context, arguments)
            SchedulerTools.LIST -> list(context.session)
            else -> failure("unknown tool")
        }
    }

    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        if (!toggles.get(SchedulerEnabled)) return
        // Bounded: the bus only buffers, but a stuck collector must not hold the finished turn.
        withTimeoutOrNull(PUBLISH_TIMEOUT) { bus.publish(EventKeys.turnFinished(session), EventOrigin.Host) }
            ?: log.w { "turn_finished of a session was not published in time" }
    }

    private suspend fun sleep(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val note = arguments.text(Arguments.NOTE).orEmpty()
        if (note.length > SchedulerLimits.MAX_NOTE) return failure("the note is too long")
        val now = clock.now()
        val condition = when (val parsed = parseCondition(arguments, now)) {
            is Parsed.Invalid -> return failure(parsed.message)
            is Parsed.Valid -> parsed.condition
        }
        alreadyHappened(condition)?.let { return failure(it) }
        val request = WakeRequest(
            newWakeId(),
            context.session,
            context.workspace,
            condition,
            note,
            WakeOrigin.Agent(context.turn),
            context.target,
        )
        val outcome = scheduler.schedule(request, now)
        log.i { "sleep ${request.id} events=${condition.events.size} timed=${condition.deadline != null}: $outcome" }
        return when (outcome) {
            is ScheduleOutcome.Scheduled -> AgentToolResult(
                "Sleeping: wake ${request.id} is pending. End your turn now; this session resumes with a new " +
                    "message when ${condition.describe()}.",
            )

            is ScheduleOutcome.Rejected, ScheduleOutcome.NotTaken, ScheduleOutcome.Unconfirmed ->
                failure(outcome.failureMessage())
        }
    }

    private suspend fun signal(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val name = arguments.text(Arguments.NAME) ?: return failure("a signal needs a name")
        val payload = arguments.text(Arguments.PAYLOAD)
        if ((payload?.length ?: 0) > SchedulerLimits.MAX_PAYLOAD) return failure("the payload is too long")
        val key = EventKey.parse("${EventNamespace.Custom.prefix}.$name")
            ?: return failure("invalid signal name: use dot-separated [a-z0-9_-] segments")
        bus.publish(key, EventOrigin.Session(context.session), payload)
        return AgentToolResult("Published $key.")
    }

    private suspend fun cancel(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val id = arguments.text(Arguments.WAKE_ID)?.let(WakeId::parse) ?: return failure("unknown wake id")
        val result = machine.send(SchedulerIntent.Public.Cancel(id, context.session))
        log.i { "cancel $id -> $result" }
        return if (result == SendResult.Accepted) {
            AgentToolResult("Cancelled wake $id.")
        } else {
            failure("no pending wake $id")
        }
    }

    private fun list(session: SessionRef): AgentToolResult {
        val ready = machine.state.value as? SchedulerState.Ready
        val own = ready?.wakes.orEmpty().filter { it.session == session }
        val text = buildString {
            appendLine("Your turn_finished key: ${EventKeys.turnFinished(session)}")
            appendLine("System keys: ${EventKeys.NetworkAvailable}, ${EventKeys.NetworkLost}")
            if (own.isEmpty()) {
                append("No pending wakes.")
            } else {
                append("Pending wakes:")
                own.forEach { wake ->
                    append("\n- ${wake.id}: ${wake.request.condition.describe()}")
                    if (wake.id in ready?.delivering.orEmpty()) append(" (waking now)")
                }
            }
        }
        return AgentToolResult(text)
    }

    /**
     * Why waiting for a network key would never wake the session: the network is already in that state. Keys are
     * edge-triggered, so the agent must act now instead.
     */
    private suspend fun alreadyHappened(condition: WakeCondition): String? {
        val awaited = condition.events
        if (EventKeys.NetworkAvailable !in awaited && EventKeys.NetworkLost !in awaited) return null
        return when (network.current()) {
            true -> "the network is already available".takeIf { EventKeys.NetworkAvailable in awaited }
            false -> "the network is already lost".takeIf { EventKeys.NetworkLost in awaited }
            null -> null
        }
    }

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private companion object {
        val PUBLISH_TIMEOUT = 1.seconds

        val INSTRUCTIONS = """
            Scheduler: instead of waiting inside a turn (polling, sleep commands), call ${SchedulerTools.SLEEP} and end
            your turn. The session resumes with a new message when one of the events arrives or the deadline passes;
            the message repeats your note. Event keys: system.network.available / system.network.lost,
            session.<id>.turn_finished of another session (it prints its key with ${SchedulerTools.LIST}),
            custom.<name> signals published with ${SchedulerTools.SIGNAL}.
        """.trimIndent()

        val SLEEP_SPEC = AgentToolSpec(
            SchedulerTools.SLEEP,
            "Put this session to sleep until an event or a deadline, then end the turn. Give at least one of " +
                "${Arguments.EVENTS}, ${Arguments.AT}, ${Arguments.AFTER_SECONDS}.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    stringArrayProperty(Arguments.EVENTS, "Event keys; the first one to arrive wakes the session")
                    stringProperty(Arguments.AT, "Deadline as an ISO-8601 instant, e.g. 2026-10-05T18:00:00Z")
                    integerProperty(Arguments.AFTER_SECONDS, "Deadline in seconds from now (a timer)")
                    stringProperty(Arguments.NOTE, "What to do after waking; it is given back to you")
                }
                required(Arguments.NOTE)
            },
        )

        val SIGNAL_SPEC = AgentToolSpec(
            SchedulerTools.SIGNAL,
            "Publish the signal custom.<name> that sleeping sessions may wait for.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    stringProperty(Arguments.NAME, "Dot-separated [a-z0-9_-] segments, e.g. tests.green")
                    stringProperty(Arguments.PAYLOAD, "Optional text handed to the woken sessions")
                }
                required(Arguments.NAME)
            },
        )

        val CANCEL_SPEC = AgentToolSpec(
            SchedulerTools.CANCEL,
            "Cancel a pending wake of this session.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    stringProperty(Arguments.WAKE_ID, "Wake id from ${SchedulerTools.SLEEP}")
                }
                required(Arguments.WAKE_ID)
            },
        )

        val LIST_SPEC = AgentToolSpec(
            SchedulerTools.LIST,
            "List this session's pending wakes and the keys it can wait for.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        )
    }
}

private sealed interface Parsed {
    data class Valid(val condition: WakeCondition) : Parsed
    data class Invalid(val message: String) : Parsed
}

/** Events and a deadline from the arguments; `at` and `after_seconds` are exclusive. */
private fun parseCondition(arguments: JsonObject, now: Instant): Parsed {
    val rawEvents = arguments.texts(Arguments.EVENTS).orEmpty()
    val events = rawEvents.map { EventKey.parse(it) ?: return Parsed.Invalid("invalid event key \"$it\"") }.toSet()
    if (events.size > SchedulerLimits.MAX_EVENTS) return Parsed.Invalid("at most ${SchedulerLimits.MAX_EVENTS} events")
    val at = arguments.text(Arguments.AT)
    val after = arguments.whole(Arguments.AFTER_SECONDS)
    if (at != null && after != null) return Parsed.Invalid("give either ${Arguments.AT} or ${Arguments.AFTER_SECONDS}")
    val deadline = when {
        at != null -> Instant.parseOrNull(at) ?: return Parsed.Invalid("${Arguments.AT} is not an ISO-8601 instant")
        after != null && after > 0 -> now + after.seconds
        after != null -> return Parsed.Invalid("${Arguments.AFTER_SECONDS} must be positive")
        else -> null
    }
    if (events.isEmpty() && deadline == null) return Parsed.Invalid("give events or a deadline")
    return Parsed.Valid(WakeCondition(events, deadline))
}
