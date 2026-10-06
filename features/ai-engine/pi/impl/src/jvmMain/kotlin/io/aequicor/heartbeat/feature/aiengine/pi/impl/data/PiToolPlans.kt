package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
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
) {
    val names: Set<String> get() = policy.nativeOn + search + hosted.map { it.name }
    override fun toString(): String = "PiDesiredTools"
}

/** Computes a fresh launch plan and detects only growth; removed tools are denied by the live host gate. */
internal class PiToolPlans(
    private val environment: PiSessionEnvironment,
    private val hosted: PiHostedSessionTools,
    private val areDetachedToolsEnabled: Boolean,
    private val scope: () -> AgentToolScope,
) {
    private val log = Log.tag("PiToolPlans")

    suspend fun desired(): PiDesiredTools {
        val scope = scope()
        val policy = environment.tools.nativeTools(
            ToolPolicyScope(scope.target?.engine, scope.workspace, scope.session, scope.target),
        )
        val search = if (environment.toggles.get(SearchEngineTools)) SearchTools - policy.hostedDenied else emptySet()
        return PiDesiredTools(scope, policy, specifications(scope), search)
    }

    suspend fun launch(): PiLaunchPlan {
        val desired = desired()
        return PiLaunchPlan(
            desired.policy,
            hosted.prepare(desired.scope, desired.hosted),
            desired.search,
            desired.names,
        )
    }

    private suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
        if (scope.workspace == null && (!areDetachedToolsEnabled || !environment.bridge.isAvailable)) return emptyList()
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
}

internal val SearchTools = setOf("web_search", "web_fetch")
