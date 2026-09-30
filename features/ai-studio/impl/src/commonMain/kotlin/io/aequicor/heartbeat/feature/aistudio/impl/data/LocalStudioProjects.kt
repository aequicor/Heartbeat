package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioLocalProjects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioDirectoryPicker
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProjects
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

@Inject
@ContributesBinding(ProfileScope::class)
internal class LocalStudioProjects(
    private val workspaces: LocalWorkspaces,
    private val picker: StudioDirectoryPicker,
    toggles: FeatureToggles,
) : StudioProjects {
    private val log = Log.tag("StudioProjects")
    override val availability: Flow<Boolean> = combine(
        toggles.observe(StudioLocalProjects),
        toggles.observe(StudioEngineRuntime),
    ) { projects, runtime -> projects && runtime && workspaces.isAvailable && picker.isAvailable }

    override val worktreeAvailability: Flow<Boolean> = combine(
        toggles.observe(WorktreeModeEnabled),
        toggles.observe(StudioEngineRuntime),
    ) { enabled, runtime -> enabled && runtime && workspaces.isAvailable }

    override suspend fun choose(): String? {
        check(availability.first()) { "Local project selection is unavailable" }
        log.i { "Choose local project" }
        val directory = picker.pick() ?: return null
        check(availability.first()) { "Local project selection was disabled" }
        val workspace = workspaces.register(directory)
        log.i { "Selected local project" }
        return workspace.ref.value
    }
}
