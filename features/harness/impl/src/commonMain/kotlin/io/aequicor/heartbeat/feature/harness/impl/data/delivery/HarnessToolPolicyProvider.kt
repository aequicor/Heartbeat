package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess

/**
 * Preserves Off dominance across active harnesses, independent of order. Catalog/gated/native-toggle checks
 * remain in the facade resolver. Resolution errors propagate: only declaration callers may use defaults.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessToolPolicyProvider(private val access: Lazy<HarnessActiveAccess>) : AgentToolPolicyProvider {
    override suspend fun policy(scope: ToolPolicyScope): ToolPolicy? {
        val active = access.value.active(scope.workspace, scope.session)
        if (active.isEmpty()) return null
        val engine = scope.engine ?: scope.target?.engine ?: scope.session?.engine
        val native = linkedMapOf<String, ToolSwitch>()
        for (harness in active) {
            for ((name, switch) in harness.tools.native[engine?.value].orEmpty()) {
                if (native[name] != ToolSwitch.Off) native[name] = switch
            }
        }
        return ToolPolicy(active.flatMapTo(linkedSetOf()) { it.tools.hostedOff }, native)
    }
}
