package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import kotlinx.serialization.json.JsonObject

/** Immutable policy snapshot used for both process launch and the native start/resume request. */
internal data class CodexThreadRequest(
    val nativeId: String?,
    val target: EngineTarget,
    val route: ExecutionRoute,
    val areDetachedToolsEnabled: Boolean,
    val policy: ResolvedToolPolicy,
) {
    val off: CodexNativeOff get() = CodexNativeOff("shell" in policy.nativeOff, "web_search" in policy.nativeOff)
}

/** Thread preparation carries no user prompt; it is safe to finish before retiring the previous process. */
internal data class CodexPreparedThread(
    val request: CodexThreadRequest,
    val params: JsonObject,
    val hosted: HostedOpening,
) {
    val instructions: String get() = params.text("developerInstructions").orEmpty()
    override fun toString(): String = "CodexPreparedThread"
}

/**
 * Rotates only the execution connection of a retained session. The session's connection mutex covers prepare
 * through Submit acceptance and idle retirement; this owner never acquires the runtime's commands mutex.
 * A failed cold resume leaves the old closed connection in place so the next send retries without losing leases.
 */
internal class CodexExecution(
    private val session: CodexSession,
    initial: CodexConnection,
    opening: CodexPreparedThread?,
    private val onConnected: (CodexConnection) -> Unit,
    private val audit: (JsonObject) -> Unit,
) {
    private val runtime get() = session.runtime
    private val log = Log.tag("CodexExecution")
    private val scope get() = ToolPolicyScope(session.ref.engine, session.route.workspace, session.ref, session.target)
    private val areDetachedToolsEnabled = opening?.request?.areDetachedToolsEnabled ?: session.areHostedToolsEnabled
    private var applied = opening?.request?.policy ?: ResolvedToolPolicy()
    private var isConcreteScope = opening?.request?.nativeId != null || opening == null
    private var instructions = opening?.instructions.orEmpty()
    var connection: CodexConnection = initial
        private set

    /** No history or Submit mutation occurs until a strict, current policy has a validated connection. */
    suspend fun prepare() {
        repeat(MAX_ATTEMPTS) { if (prepareCurrent()) return }
        fail(EngineFailure.Session(SessionFailureReason.Changed))
    }

    private suspend fun prepareCurrent(): Boolean {
        session.ensureReadyForPolicy()
        val request = runtime.threads.request(
            session.ref.nativeId,
            session.target,
            session.route,
            areDetachedToolsEnabled,
        )
        return when {
            !connection.isClosed && isConcreteScope && request.policy == applied -> true
            canRebase(request) -> rebase(request)
            else -> reopen(request)
        }
    }

    private suspend fun rebase(request: CodexThreadRequest): Boolean {
        val prepared = runtime.threads.prepare(request, connection)
        if (runtime.threads.policy(scope) != request.policy) return false
        return if (prepared.instructions == instructions) {
            session.ensureReadyForPolicy()
            applied = request.policy
            isConcreteScope = true
            true
        } else {
            reopen(request)
        }
    }

    /** Generations from different scopes are incomparable; rebase once only when content and instructions match. */
    private fun canRebase(request: CodexThreadRequest): Boolean = !isConcreteScope && !connection.isClosed &&
        request.policy.copy(generation = 0) == applied.copy(generation = 0)

    private suspend fun reopen(request: CodexThreadRequest): Boolean {
        val previous = connection
        val candidate = runtime.openConnection(request.off)
        var isTransferred = false
        try {
            val prepared = runtime.threads.prepare(request, candidate)
            session.ensureReadyForPolicy()
            if (!previous.isClosed) runtime.materialize(session)
            session.ensureReadyForPolicy()
            runtime.discard(previous)
            val response = runtime.threads.start(prepared, candidate)
            session.ensureReadyForPolicy()
            audit(response.obj("thread"))
            if (runtime.threads.policy(scope) != request.policy) return false
            session.ensureReadyForPolicy()
            connection = candidate
            onConnected(candidate)
            applied = request.policy
            isConcreteScope = true
            instructions = prepared.instructions
            candidate.bind(session)
            isTransferred = true
            log.i { "Codex native policy applied through cold resume" }
            return true
        } finally {
            if (!isTransferred) runtime.discard(candidate)
        }
    }

    /** A policy change while preparing prompt resources is a proven rejection, before any native turn/start. */
    suspend fun confirm(request: RequestId) {
        val policy = try {
            runtime.threads.policy(scope)
        } catch (e: EngineException) {
            log.w(e) { "Codex submission refused: execution policy unavailable" }
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request))
        }
        if (connection.isClosed || policy != applied) {
            fail(EngineFailure.Request(RequestFailureReason.Invalid, request))
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
