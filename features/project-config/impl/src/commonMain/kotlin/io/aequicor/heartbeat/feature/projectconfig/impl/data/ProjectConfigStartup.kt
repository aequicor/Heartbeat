package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorage
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEnabled
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectConfigMachine
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectFolders
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Starts the machine with the profile and feeds it what no state effect may observe, since effects end with their
 * state: the toggle and the user's projects, and while the feature is on, changes of `.heartbeat` folders (their
 * fingerprints are polled; a sync reads a folder only when it changed) and of entities in the profile libraries.
 * Desktop only.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ProjectConfigStartup(
    private val machine: Lazy<ProjectConfigMachine>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val toggles: FeatureToggles,
    // Lazy: profile storages may refuse to open (a wipe in progress) while the set of startups is created.
    private val workspaces: Lazy<LocalWorkspaces>,
    private val folders: ProjectFolders,
    private val access: Lazy<ConfigFolderAccess>,
    private val sections: Lazy<Set<ConfigSection>>,
) : ProfileStartup {
    private val log = Log.tag("ProjectConfigStartup")

    override fun start() {
        if (!folders.isAvailable || !workspaces.value.isAvailable) {
            log.d { "Project configuration is unsupported on this platform" }
            return
        }
        val machine = machine.value
        scope.coroutineScope.launch {
            combine(toggles.observe(ProjectConfigEnabled), workspaces.value.observe()) { isEnabled, projects ->
                isEnabled to projects.map { it.ref }.toSet()
            }
                .distinctUntilChanged()
                .catch { e -> log.e(e) { "Project configuration inputs stopped" } }
                .collect { (isEnabled, projects) ->
                    log.i { "Project configuration enabled=$isEnabled for ${projects.size} projects" }
                    if (isEnabled) {
                        machine.send(Internal.Enabled(projects))
                        machine.send(Internal.ProjectsChanged(projects))
                    } else {
                        machine.send(Internal.Disabled)
                    }
                }
        }
        // Folders and entities are watched only while the feature is on: nothing polls in a profile that never uses it.
        scope.coroutineScope.launch {
            machine.state.map { it is ProjectConfigState.Ready }
                .distinctUntilChanged()
                .collectLatest { isReady ->
                    if (isReady) {
                        coroutineScope {
                            launch { watchFolders(machine) }
                            launch { watchEntities(machine) }
                        }
                    }
                }
        }
    }

    private suspend fun watchFolders(machine: ProjectConfigMachine) {
        val prints = mutableMapOf<WorkspaceRef, String?>()
        while (currentCoroutineContext().isActive) {
            delay(POLL_MILLIS)
            val ready = machine.state.value as? ProjectConfigState.Ready
            val projects = ready?.projects?.keys.orEmpty().filter {
                ready?.storage?.location(it) == ConfigStorage.Project
            }.toSet()
            prints.keys.retainAll(projects)
            for (project in projects) {
                val print = access.value.fingerprint(project)
                if (prints.containsKey(project) && prints[project] != print) {
                    log.v { "A project configuration folder changed" }
                    machine.send(Internal.Changed(project))
                }
                prints[project] = print
            }
        }
    }

    private suspend fun watchEntities(machine: ProjectConfigMachine) {
        val flows = sections.value.map { it.entities }
        if (flows.isEmpty()) return
        combine(flows) { it.toList() }
            .distinctUntilChanged()
            .drop(1)
            .catch { e -> log.e(e) { "Configuration entities stopped" } }
            .collectLatest { _ ->
                // Bursts of library writes settle before the projects are synced again.
                delay(SETTLE_MILLIS)
                val ready = machine.state.value as? ProjectConfigState.Ready
                val projects = ready?.projects?.keys.orEmpty().filter {
                    ready?.storage?.location(it) == ConfigStorage.Project
                }.toSet()
                log.v { "Configuration entities changed, syncing ${projects.size} projects" }
                projects.forEach { project -> machine.send(Internal.Changed(project)) }
            }
    }

    private companion object {
        const val POLL_MILLIS = 3_000L
        const val SETTLE_MILLIS = 500L
    }
}
