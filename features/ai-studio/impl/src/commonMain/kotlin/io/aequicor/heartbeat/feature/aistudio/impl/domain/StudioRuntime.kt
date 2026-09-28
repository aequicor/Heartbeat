package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import kotlinx.coroutines.flow.StateFlow

/** Profile service: accepted work and history observation outlive any individual screen. */
interface StudioRuntime {
    val state: StateFlow<StudioRuntimeState>

    /** Reads the profile model preference for a new conversation. */
    suspend fun defaults(): RunSettings

    /** Starts profile-owned work and awaits its outcome; cancelling this waiter only detaches it. */
    suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome

    /** Requests native interruption; only native terminal state completes the run. */
    suspend fun cancel(sessionId: String)

    /** Sends an exact currently pending permission option. */
    suspend fun respond(sessionId: String, requestId: String, optionId: String)
}
