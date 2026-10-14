package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Main-confined process ownership; an unconfirmed exit retains the process for retry. */
internal class PiProcessLifetime(
    private val dispatchers: DispatcherProvider,
    private val event: suspend (JsonObject) -> Unit,
    private val failed: suspend (EngineFailure) -> Unit,
) {
    private var opening: CompletableDeferred<Unit>? = null
    private var isShutdownConfirmed = false
    var connection: PiConnection? = null
    var generation = 0
        private set
    var launchedTools: Set<String> = emptySet()
        private set
    var launchedInstructions: String? = null
        private set
    val contextRevision = PiContextRevision()
    var isShuttingDown = false
        private set
    val isReleaseBlocked: Boolean get() = isShuttingDown && !isShutdownConfirmed

    fun beginShutdown() {
        isShuttingDown = true
    }

    /** Callbacks of a replaced or failed process no longer belong to this session. */
    fun discard() {
        generation++
    }

    /** Covers launch preparation too, so shutdown cannot miss a process returned after its first snapshot. */
    suspend fun open(factory: PiConnector, prepare: suspend () -> PiLaunchPlan): PiConnection {
        val pending = CompletableDeferred<Unit>().also { opening = it }
        try {
            val plan = prepare()
            val current = ++generation
            contextRevision.renewed()
            val fresh = factory(
                plan,
                { record ->
                    withContext(dispatchers.main) {
                        if (!isShuttingDown && current == generation) event(record)
                    }
                },
                { failure ->
                    withContext(dispatchers.main) {
                        if (!isShuttingDown && current == generation) failed(failure)
                    }
                },
            )
            // Shutdown must own the replacement before recovery can reject and close its transport.
            connection = fresh
            launchedTools = plan.requestedNames
            launchedInstructions = plan.requestedInstructions
            return fresh
        } finally {
            opening = null
            pending.complete(Unit)
        }
    }

    /** Reads the connection after startup; null permits cleanup without claiming a stopped native turn. */
    suspend fun stop(): Boolean? {
        opening?.await()
        val isStopped = connection?.stopAndAwait()
        if (isStopped == false) piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        isShutdownConfirmed = true
        return isStopped
    }
}
