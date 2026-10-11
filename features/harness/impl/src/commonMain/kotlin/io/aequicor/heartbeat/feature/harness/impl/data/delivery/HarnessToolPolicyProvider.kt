package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess

/**
 * Preserves Off dominance across harnesses, independent of order. Restrictions (Off, hosted_off) come from every
 * applicable harness, uncapped, so that harnesses sorting earlier by name cannot displace a restricting one; On
 * switches come only from the capped active set (the name-ordered prefix). Catalog/gated/native-toggle checks
 * remain in the facade resolver. Resolution errors propagate: only declaration callers may use defaults.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessToolPolicyProvider(private val access: Lazy<HarnessActiveAccess>) : AgentToolPolicyProvider {
    override suspend fun policy(scope: ToolPolicyScope): ToolPolicy? {
        val applicable = access.value.applicable(scope.workspace, scope.session)
        if (applicable.isEmpty()) return null
        // The active set is the name-ordered prefix of the same snapshot, so both come from one resolution.
        val active = applicable.take(HarnessLimits.ACTIVE_PER_SESSION).mapTo(hashSetOf()) { it.id }
        val engine = scope.engine ?: scope.target?.engine ?: scope.session?.engine
        val native = linkedMapOf<String, ToolSwitch>()
        for (harness in applicable) {
            for ((name, switch) in harness.tools.native[engine?.value].orEmpty()) {
                val isContributing = switch == ToolSwitch.Off || harness.id in active
                if (isContributing && native[name] != ToolSwitch.Off) native[name] = switch
            }
        }
        return ToolPolicy(applicable.flatMapTo(linkedSetOf()) { it.tools.hostedOff }, native)
    }
}
