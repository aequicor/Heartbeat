package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import kotlinx.coroutines.flow.Flow

/** Lazy dispatcher access breaks the contribution cycle: the dispatcher is built from this feature's tools too. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeHarnessToolCatalogs(
    private val tools: Lazy<ProfileAgentTools>,
    private val facade: Lazy<EngineFacade>,
    private val toggles: FeatureToggles,
) : HarnessToolCatalogs {
    override fun nativeEnabling(): Flow<Boolean> = toggles.observe(HarnessNativeTools)

    override fun hosted(): List<ToolGroup> = tools.value.catalog()

    override fun engines(): List<EngineDescriptor> = facade.value.engines.state.value.map { it.descriptor }

    override suspend fun effective(scope: ToolPolicyScope): ResolvedToolPolicy = tools.value.nativeTools(scope)
}
