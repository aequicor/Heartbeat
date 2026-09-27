package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAvailability
import kotlinx.coroutines.flow.Flow

/**
 * Workspace of the studio: projects, sessions, split panes and the offline demo agent.
 * Enabled by default; switching it off restores the placeholder screen, also while the studio is open.
 */
internal val StudioWorkspaceToggle: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.workspace",
    description = "Рабочее пространство AI-студии: проекты, сессии и демо-агент",
    default = true,
)

/** Reads the workspace toggle through the app-owned toggle service; the service logs values and changes. */
@Inject
@ContributesBinding(AiStudioScope::class)
internal class ToggleStudioAvailability(private val toggles: FeatureToggles) : StudioAvailability {
    override suspend fun isEnabled(): Boolean = toggles.get(StudioWorkspaceToggle)

    override fun observe(): Flow<Boolean> = toggles.observe(StudioWorkspaceToggle)
}
