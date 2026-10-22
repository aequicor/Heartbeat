package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.FrozenHostedTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolChoices
import io.aequicor.heartbeat.feature.aistudio.impl.domain.browserMentionDirective

/** Opens the chat's native context and carries incompatible frozen declarations across a proven idle boundary. */
internal interface StudioSessionHost {
    /** [preselected] reaches only a session created now ([CreateSessionRequest.preselected]); a resume ignores it. */
    suspend fun openTurn(id: String, settings: RunSettings, preselected: ToolChoices = emptyMap()): ActiveSession

    /** Deferred choices were absent from [active] creation; a frozen adapter requires a fresh context. */
    suspend fun needsCarriedTools(id: String, active: ActiveSession): Boolean

    /** Carries proven idle [active] into a new context, preserving history before its handle is detached. */
    suspend fun rolloverTurn(request: StudioTurnRequest, active: ActiveSession)
}

/** Native session and its outside routing lease; the executor retains the lease through native settlement. */
internal data class StudioEnvironmentSession(val active: ActiveSession, val lease: AutoCloseable?)

/**
 * A retained native tool list must match the outside host and any deferred harness carry before a prompt is sent.
 * Incompatible idle sessions continue through history carry; an active or uncertain session is never replaced.
 */
internal suspend fun StudioSessionHost.openEnvironmentSession(
    request: StudioTurnRequest,
    preselected: ToolChoices,
): StudioEnvironmentSession {
    var active = openTurn(request.id, request.settings, preselected)
    val environment = request.environment
    var lease: AutoCloseable? = environment?.bind(active.ref)
    var isTransferred = false
    try {
        if (needsNewDeclarations(request, active)) {
            check(request.recoveryRequests.isEmpty()) { "A recovering request cannot replace its native session" }
            check(active.canCarryEnvironment()) { "The previous native turn is still active or uncertain" }
            val inspection = active.features.requireFeature(RestoresSessionTurns).inspect(null)
            check(inspection == TurnInspection.Idle && active.canCarryEnvironment()) {
                "The native session is not confirmed idle"
            }
            log.i { "Continue chat with fresh hosted declarations" }
            rolloverTurn(request, active)
            lease?.close()
            lease = null
            active = openTurn(request.id, request.settings, preselected)
            lease = environment?.bind(active.ref)
            check(!needsNewDeclarations(request, active)) { "New native session has incompatible hosted tools" }
        }
        isTransferred = true
        return StudioEnvironmentSession(active, lease)
    } finally {
        if (!isTransferred) lease?.close()
    }
}

/** Local refusal barrier; an outside rollover additionally requires fresh native inspection. */
internal fun ActiveSession.canCarryEnvironment(): Boolean {
    val ready = state.value as? ActiveSessionState.Ready ?: return false
    return ready.lastTurn?.outcome != TurnOutcome.Unknown
}

private suspend fun StudioSessionHost.needsNewDeclarations(request: StudioTurnRequest, active: ActiveSession): Boolean {
    val isCarryPending = needsCarriedTools(request.id, active)
    val isEmbeddedRequested = request.environment == null && request.isUserPrompt &&
        request.userInstruction?.let { browserMentionDirective(it, isExternal = false) } != null
    if (request.environment == null && !isCarryPending && !isEmbeddedRequested) return false
    return when (val access = active.features.resolve(FrozenHostedTools)) {
        is FeatureAccess.Available -> {
            val hasIncompatibleTools = access.feature.names.any { request.environment?.acceptsTool(it) == false }
            // A chat opened in the extension may have permanently omitted embedded declarations. Carry it
            // back only for an explicit local browser request, using the same idle/history barriers.
            val hasExternalOnly = isEmbeddedRequested && access.feature.names.any { it.startsWith("ex_control_") } &&
                access.feature.names.none { it.startsWith("browser_") }
            isCarryPending || hasIncompatibleTools || hasExternalOnly
        }

        is FeatureAccess.Unavailable -> throw EngineException(access.reason)

        FeatureAccess.Unsupported -> false
    }
}

private val log = Log.tag("StudioEnvironmentSession")
