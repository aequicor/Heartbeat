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

/**
 * Non-serializable host provenance for native questionnaire delivery, never an engine/model-controlled flag.
 * Preserving questionnaire originals does not grant action authority. All existing respond calls are manual/unknown.
 */
sealed interface StudioPermissionAnswerOrigin {
    /** Default for human answers and every caller without native autopilot provenance. */
    data object ManualOrUnknown : StudioPermissionAnswerOrigin

    /**
     * Minted only by the host bridge from a verified native autopilot context. The captured request and callbacks
     * must be checked at delivery before revocation; only operational eligibility is checked after revocation.
     */
    class NativeAutomatic internal constructor(
        internal val permission: StudioPermission,
        isAuthorityCurrent: suspend () -> Boolean,
        isCurrent: suspend () -> Boolean,
    ) : StudioPermissionAnswerOrigin {
        private val authorityCurrent = isAuthorityCurrent
        private val current = isCurrent

        internal suspend fun isAuthorityCurrent(): Boolean = authorityCurrent()
        internal suspend fun isCurrent(): Boolean = current()
    }
}

/** Profile service: accepted work and history observation outlive any individual screen. */
interface StudioRuntime :
    StudioRuntimeConfiguration,
    StudioPermissionResponder {
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

    /**
     * Prepares a host-generated delivery and asks [admission] immediately before submission. Cancellation of this
     * caller before admission revokes preparation; admitted native work retains profile ownership. Older runtimes
     * deliberately refuse this overload instead of silently ignoring the gate or accepting before preparation.
     */
    // Preserve existing overloads and their named arguments while gated runtimes opt in explicitly.
    @Suppress("LongParameterList")
    suspend fun run(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        submissionId: String = "",
        environment: StudioChatEnvironment? = null,
        userInstruction: String? = null,
        admission: StudioRunAdmission,
        onAccepted: suspend () -> Unit,
    ): RunOutcome = error("Host run admission is unavailable")

    /** Revokes durable and live human authority before a generated questionnaire follow-up is submitted. */
    suspend fun invalidateUserInstructions(sessionId: String) {
        error("Human authority revocation is unavailable")
    }

    /** Requests native interruption; only native terminal state completes the run. */
    suspend fun cancel(sessionId: String)
}

/** Native permission delivery with optional host-only provenance; command admission alone is not success. */
interface StudioPermissionResponder {
    /**
     * Sends an exact currently pending permission option and awaits engine acknowledgement of that request.
     * Fails on late delivery failure, terminal turn or lost handle; command admission alone is not success.
     */
    suspend fun respond(sessionId: String, requestId: String, optionId: String, answer: StudioPermissionAnswer? = null)

    /**
     * Delivers host-proven automatic provenance with a captured native permission. Existing runtimes fail closed
     * for automatic answers; ordinary callers keep the manual/unknown overload below.
     */
    suspend fun respond(
        permission: StudioPermission,
        optionId: String,
        answer: StudioPermissionAnswer?,
        origin: StudioPermissionAnswerOrigin,
    ) {
        check(origin == StudioPermissionAnswerOrigin.ManualOrUnknown) { "Automatic answer provenance is unavailable" }
        respond(permission, optionId, answer)
    }

    /** Delivers an observed permission only to its captured native turn, refusing a reused request id. */
    suspend fun respond(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer? = null) {
        error("Captured permission delivery is unavailable")
    }
}
