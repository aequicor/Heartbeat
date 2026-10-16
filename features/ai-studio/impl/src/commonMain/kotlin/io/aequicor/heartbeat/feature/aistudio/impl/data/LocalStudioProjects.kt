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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    /** Folders are probed again whenever the catalog changes, so a newly initialised repository appears then. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val repositoryProjects: Flow<Set<String>> = worktreeAvailability.distinctUntilChanged()
        .flatMapLatest { isAvailable ->
            if (!isAvailable) {
                flowOf(emptySet())
            } else {
                workspaces.observe().map { projects ->
                    projects.filterTo(mutableSetOf()) { project ->
                        workspaces.resolve(project.ref)?.let { picker.isRepository(it) } == true
                    }.mapTo(mutableSetOf()) { it.ref.value }
                        .also { log.d { "Git repository projects ${it.size} of ${projects.size}" } }
                }
            }
        }.distinctUntilChanged()

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
