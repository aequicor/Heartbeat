package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.runs

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.MAX_SUBAGENT_RUNS
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRun
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRunStore
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentRequest
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStart
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStatus
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Publishes subagent starts in the profile scope: durable reservation precedes scheduler admission and cancelling
 * the tool's wait does not cancel publication. The runtime gate is installed before the reservation can be seen,
 * so receive never mistakes an in-flight start for a missing scheduler action. After startup exits, or after
 * restart, an absent scheduler record proves admission did not survive: scheduler journals before native work.
 * Uncertain starts are never retried. Result reads are serialized separately from startup.
 */
@SingleIn(ProfileScope::class)
internal class SubagentStarts(
    private val scope: CoroutineScope,
    private val runs: SubagentRunStore,
    private val agents: Lazy<BackgroundAgents>,
) {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        runs: SubagentRunStore,
        agents: Lazy<BackgroundAgents>,
    ) : this(scope.coroutineScope, runs, agents)

    private val log = Log.tag("SubagentStarts")
    private val gate = Mutex()
    private val reading = Mutex()
    private val pending = mutableSetOf<String>()

    /** Starts only after a durable reservation. Only a confirmed refusal rolls that reservation back. */
    suspend fun start(run: SubagentRun, request: BackgroundAgentRequest): BackgroundAgentStart {
        val startup = gate.withLock {
            check(pending.add(run.id)) { "Subagent start already pending" }
            scope.async(start = CoroutineStart.LAZY) { publish(run, request) }
        }
        startup.start()
        return startup.await()
    }

    /** State of a reserved run, including starts that have not reached the scheduler yet. */
    suspend fun status(run: SubagentRun): BackgroundAgentStatus {
        if (gate.withLock { run.id in pending }) return BackgroundAgentStatus.Running
        val status = agents.value.status(ActionId(run.id), SubagentTools.OWNER)
        return if (status == BackgroundAgentStatus.Unknown) {
            BackgroundAgentStatus.Finished(false, "Startup was interrupted before a scheduler action was retained.")
        } else {
            status
        }
    }

    /** Keeps concurrent receives from acknowledging the same result twice. Does not block startup. */
    suspend fun <T> read(block: suspend () -> T): T = reading.withLock { block() }

    private suspend fun publish(run: SubagentRun, request: BackgroundAgentRequest): BackgroundAgentStart = try {
        if (!runs.reserve(run)) {
            BackgroundAgentStart.Refused("the profile already retains $MAX_SUBAGENT_RUNS unread subagents")
        } else {
            agents.value.start(request).also { result ->
                if (result is BackgroundAgentStart.Refused) runs.remove(setOf(run.id))
                log.i { "subagent ${run.id} startup confirmed: started=${result is BackgroundAgentStart.Started}" }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = IllegalStateException("Subagent startup failed (${e::class.simpleName.orEmpty()})")
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) {
            "subagent ${run.id} startup failed; reservation kept for reconciliation"
        }
        throw failure
    } finally {
        withContext(NonCancellable) { gate.withLock { pending.remove(run.id) } }
    }
}
