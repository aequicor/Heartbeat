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
