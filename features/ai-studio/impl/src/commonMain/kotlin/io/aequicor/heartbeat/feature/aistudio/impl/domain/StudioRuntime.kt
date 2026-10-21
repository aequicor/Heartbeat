package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Profile service: accepted work and history observation outlive any individual screen. */
interface StudioRuntime : StudioRuntimeConfiguration {
    val state: StateFlow<StudioRuntimeState>

    /**
     * Observes provider quotas for the composer model routes of one studio screen, identified by [owner];
     * screens observe independently and an empty set detaches only [owner].
     */
    suspend fun observeUsageTargets(owner: Any, modelIds: Set<String>) = Unit

    /** Explicit panel-open refresh; telemetry failure never fails a turn. */
    suspend fun refreshUsage(modelId: String) = Unit

    /** Restores start-page preferences and awaits route-scoped effort loading before the screen becomes ready. */
    suspend fun defaults(): RunSettings

    /** Observes accepted start-page choices, including choices retained in memory after a failed write. */
    fun observeDefaults(): Flow<RunSettings> = flow { emit(defaults()) }

    /** Persists start-page preferences; cancelling the screen waiter does not cancel the accepted write. */
    suspend fun saveDefaults(settings: RunSettings, version: StudioSettingsVersion) {
        error("Start-page preference persistence is unavailable")
    }

    /** Starts profile-owned work and awaits its outcome; cancelling this waiter only detaches it. */
    suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome

    /**
     * Starts a turn with durable references and reports native acceptance before awaiting completion.
     * [submissionId] names the screen submission that created a new chat, so its first turn finds what it claimed.
     * [userInstruction] is captured at an explicit human composer send, never inferred from [prompt].
     * Generated follow-ups, question titles, choice labels and service surface prompts must leave it null.
     */
    suspend fun run(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        submissionId: String = "",
        environment: StudioChatEnvironment? = null,
        userInstruction: String? = null,
        onAccepted: suspend () -> Unit,
    ): RunOutcome {
        require(environment == null) { "Outside environments are unavailable for this runtime" }
        require(attachments.isEmpty()) { "Attachments are unavailable for this runtime" }
        onAccepted()
        return run(sessionId, prompt, settings)
    }

    /** Revokes durable and live human authority before a generated questionnaire follow-up is submitted. */
    suspend fun invalidateUserInstructions(sessionId: String) {
        error("Human authority revocation is unavailable")
    }

    /** Requests native interruption; only native terminal state completes the run. */
    suspend fun cancel(sessionId: String)

    /**
     * Sends an exact currently pending permission option and awaits engine acknowledgement of that request.
     * Fails on late delivery failure, terminal turn or lost handle; command admission alone is not success.
     */
    suspend fun respond(sessionId: String, requestId: String, optionId: String, answer: StudioPermissionAnswer? = null)

    /** Delivers an observed permission only to its captured native turn, refusing a reused request id. */
    suspend fun respond(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer? = null) {
        error("Captured permission delivery is unavailable")
    }
}
