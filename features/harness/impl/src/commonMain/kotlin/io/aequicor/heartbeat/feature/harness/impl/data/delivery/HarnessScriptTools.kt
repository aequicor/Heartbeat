package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolCall
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessToolBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessToolDispatch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The ordinary facade action/trust/hook gate authorizes a pinned handler generation, never just its name. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessScriptTools(
    private val toggles: FeatureToggles,
    private val access: Lazy<HarnessActiveAccess>,
    private val runtime: Lazy<HarnessRuntime>,
    private val dispatch: Lazy<HarnessToolDispatch>,
    private val proofs: Lazy<HarnessSessionProofs>,
    private val origins: Lazy<HarnessRequestOrigins>,
) : AgentToolContribution {
    override val group: String = "harness"
    override val title: String = "Харнессы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = runtime.value.declaredTools.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        specifications(AgentToolScope(workspace))

    override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
        if (!toggles.get(HarnessEnabled)) return emptyList()
        val active = access.value.active(scope.workspace, scope.session).mapTo(linkedSetOf()) { it.id }
        return dispatch.value.bindings(active).map { it.specification }
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val binding = checkNotNull(binding(context, spec.name)) { "Harness tool is unavailable" }
        return AgentToolApproval(spec.name, spec.description, binding = binding.key)
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        val binding = binding(context, name) ?: return unavailable()
        if (context.authorization?.binding != binding.key) return unavailable()
        val origin = origins.value.origin(context.session, context.request)
        return withContext(HarnessOriginContext(origin)) {
            val result = dispatch.value.execute(binding, ScriptToolCall(context, arguments))
            AgentToolResult(result.text, result.isError)
        }
    }

    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        // The dispatch owns timed-out actual jobs even while the feature is disabled.
        if (dispatch.isInitialized()) dispatch.value.finishTurn(session, turn)
    }

    private suspend fun binding(context: AgentToolContext, name: String): HarnessToolBinding? {
        if (!toggles.get(HarnessEnabled)) return null
        val active = access.value.active(context.workspace, context.session).mapTo(linkedSetOf()) { it.id }
        proofs.value.remember(context.session, context.workspace)
        return dispatch.value.bindings(active).singleOrNull { it.specification.name == name }
    }

    private fun unavailable() = AgentToolResult("Harness tool changed or is unavailable", isError = true)
}
