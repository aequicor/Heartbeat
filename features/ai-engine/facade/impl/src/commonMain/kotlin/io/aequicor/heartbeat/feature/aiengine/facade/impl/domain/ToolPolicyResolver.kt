package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName

/** Policy IO boundary. Null is a failed execution lookup; declaration lookups always return defaults on failure. */
internal fun interface ToolPolicyResolver {
    suspend fun resolve(scope: ToolPolicyScope, hostedNames: Set<String>, isExecuting: Boolean): ResolvedToolPolicy?
}

/** Compatibility dependency for isolated hosted-tool tests and hosts without policy contributions. */
internal object NoToolPolicyResolver : ToolPolicyResolver {
    override suspend fun resolve(
        scope: ToolPolicyScope,
        hostedNames: Set<String>,
        isExecuting: Boolean,
    ): ResolvedToolPolicy = ResolvedToolPolicy()
}

/** Pure merge: unknown native names never enable execution, and Off always wins across providers. */
internal fun mergedToolPolicy(
    catalog: List<NativeToolSpec>,
    policies: List<ToolPolicy>,
    hostedNames: Set<String>,
    canEnableNative: Boolean,
): ResolvedToolPolicy {
    if (policies.any { it.isToolFree }) {
        return ResolvedToolPolicy(nativeOff = catalog.map { it.name }.toSet(), hostedDenied = hostedNames)
    }
    val off = policies.flatMap { it.native.filterValues { value -> value == ToolSwitch.Off }.keys }.toSet()
    val on = policies.flatMap { it.native.filterValues { value -> value == ToolSwitch.On }.keys }.toSet()
    return ResolvedToolPolicy(
        nativeOn = catalog.filter { tool ->
            tool.name !in off && (
                tool.isEnabledByDefault ||
                    (canEnableNative && tool.isGated && tool.name in on && tool.name !in hostedNames)
            )
        }.map { it.name }.toSet(),
        nativeOff = off.intersect(catalog.map { it.name }.toSet()),
        hostedDenied = policies.flatMap { it.hostedOff }.map(::hostedToolName).toSet(),
    )
}

/**
 * Applies renamed hosted tools: a policy that disables a legacy name disables every tool that replaced it. Every
 * consumer of the resolved policy (declarations, execution, adapter-operated tools) sees the expanded set.
 */
internal class LegacyToolNamesResolver(
    private val delegate: ToolPolicyResolver,
    private val legacyNames: () -> Map<String, Set<String>>,
) : ToolPolicyResolver {
    override suspend fun resolve(
        scope: ToolPolicyScope,
        hostedNames: Set<String>,
        isExecuting: Boolean,
    ): ResolvedToolPolicy? = delegate.resolve(scope, hostedNames, isExecuting)?.withLegacyNames(legacyNames())
}

/** [ResolvedToolPolicy.hostedDenied] extended by the replacements of every denied legacy name. */
internal fun ResolvedToolPolicy.withLegacyNames(legacyNames: Map<String, Set<String>>): ResolvedToolPolicy {
    val replacements = hostedDenied.flatMap { legacyNames[it].orEmpty() }
    return if (replacements.isEmpty()) this else copy(hostedDenied = hostedDenied + replacements)
}
