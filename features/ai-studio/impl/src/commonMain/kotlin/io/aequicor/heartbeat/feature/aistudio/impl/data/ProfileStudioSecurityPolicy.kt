package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityPolicy
import kotlinx.coroutines.flow.first

/** Native submission and the composer observe the same committed profile mode as tool execution. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class ProfileStudioSecurityPolicy(settings: AgentSecuritySettings) : StudioSecurityPolicy {
    override val levels = settings.modes

    override suspend fun level(): StudioSecurityLevel = levels.first()
}
