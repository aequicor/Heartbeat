package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException

/** One process's frozen declarations. Policy is still checked at every invocation. */
internal data class PiLaunchPlan(
    val policy: ResolvedToolPolicy,
    val hosted: PiHostedTools?,
    val search: Set<String>,
    // A failed optional attachment is retried on the next process launch, not by restarting every turn.
    val requestedNames: Set<String> = policy.nativeOn + search + hosted?.specifications.orEmpty().map { it.name },
    val requestedInstructions: String? = hosted?.instructions,
) {
    val names: Set<String> get() = policy.nativeOn + search + hosted?.specifications.orEmpty().map { it.name }
    override fun toString(): String = "PiLaunchPlan"
}

/** Candidate declarations do not allocate a bridge capability or replace a running process's instructions. */
internal data class PiDesiredTools(
    val scope: AgentToolScope,
    val policy: ResolvedToolPolicy,
    val hosted: List<AgentToolSpec>,
    val search: Set<String>,
    val instructions: String?,
) {
    val names: Set<String> get() = policy.nativeOn + search + hosted.map { it.name }
    override fun toString(): String = "PiDesiredTools"
}

/** Whether a running process can keep its frozen catalog or needs new declarations before its next prompt. */
internal enum class PiToolChange { None, Added, Removed, Instructions }

/** Computes a fresh launch plan; removed tools must disappear from declarations as well as the live host gate. */
internal class PiToolPlans(
    private val environment: PiSessionEnvironment,
    private val hosted: PiHostedSessionTools,
    private val request: CreateSessionRequest,
    private val scope: () -> AgentToolScope,
) {
    private val log = Log.tag("PiToolPlans")

    suspend fun desired(): PiDesiredTools {
        val scope = scope().let { if (it.session == null) it.copy(preselected = request.preselected) else it }
        val policy = environment.tools.nativeTools(
            ToolPolicyScope(scope.target?.engine, scope.workspace, scope.session, scope.target, scope.preselected),
        )
        val search = if (environment.toggles.get(SearchEngineTools)) SearchTools - policy.hostedDenied else emptySet()
        val specs = specifications(scope)
        val instructions = if (specs.isNotEmpty() || request.areSessionHooksEnabled) {
            instructions(scope, specs)
        } else {
            ""
        }
        return PiDesiredTools(scope, policy, specs, search, instructions)
    }

    /** Stale declarations cannot survive an unavailable catalog at the next prompt boundary. */
    private suspend fun change(launched: Set<String>, instructions: String?): PiToolChange = try {
        val desired = desired()
        when {
            (launched - desired.names).isNotEmpty() -> PiToolChange.Removed
            desired.instructions != instructions -> PiToolChange.Instructions
            (desired.names - launched).isNotEmpty() -> PiToolChange.Added
            else -> PiToolChange.None
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.withoutDetails()) { "Pi tool declarations unavailable; refusing the prompt" }
        piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    /** Only a persisted transcript can carry refreshed declarations without losing native history. */
    suspend fun refreshedTranscript(
        launched: Set<String>,
        instructions: String?,
        persisted: suspend () -> String?,
    ): String? {
        val change = change(launched, instructions)
        if (change == PiToolChange.None) return null
        val file = persisted()
        // Growth can wait; removed tools and the host's changed guidance must apply before a prompt.
        if (file == null && change != PiToolChange.Added) {
            piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        return file
    }

    suspend fun launch(): PiLaunchPlan {
        val desired = desired()
        return PiLaunchPlan(
            desired.policy,
            hosted.prepare(desired.scope, desired.hosted, desired.instructions) {
                if (request.areSessionHooksEnabled) instructions(desired.scope, emptyList()) else null
            },
            desired.search,
            desired.names,
            desired.instructions,
        )
    }

    private suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
        if (scope.workspace == null && (!request.areDetachedToolsEnabled || !environment.bridge.isAvailable)) {
            return emptyList()
        }
        return try {
            environment.tools.specifications(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (scope.workspace != null) throw e
            log.w(e.withoutDetails()) { "Detached tool declarations are unavailable" }
            emptyList()
        }
    }

    /** Hooked turns require their host guidance; only an unhooked detached context may omit failed instructions. */
    private suspend fun instructions(scope: AgentToolScope, specs: List<AgentToolSpec>): String? = try {
        environment.tools.instructions(scope.copy(declared = specs.map { it.name }.toSet()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (scope.workspace != null) throw e
        log.w(e.withoutDetails()) { "Hosted instructions are unavailable" }
        if (request.areSessionHooksEnabled) piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
        null
    }
}

internal val SearchTools = setOf("web_search", "web_fetch")
