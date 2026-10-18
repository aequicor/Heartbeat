package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.BuildLimits
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.DEFAULT_SANDBOX_PAUSE_MILLIS
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.ProjectBuildPool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** The stored user limit next to this machine's automatic estimate. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class StoredBuildLimits(
    private val settings: BuildCapacitySettings,
    private val capacity: BuildCapacity,
    private val projects: ProjectBuildSettings? = null,
    private val workspaces: LocalWorkspaces? = null,
    private val journal: Lazy<WorktreeJournal>? = null,
) : BuildLimits {
    override val automaticBuilds: Int get() = capacity.automatic.parallelBuilds

    override fun observe(): Flow<Int?> = settings.observe()

    override suspend fun set(parallelBuilds: Int?) = settings.set(parallelBuilds)

    override fun projects(): Flow<List<ProjectBuildPool>> {
        val projects = projects ?: return flowOf(emptyList())
        val workspaces = workspaces ?: return flowOf(emptyList())
        return combine(workspaces.observe(), projects.changes().onStart { emit(Unit) }) { available, _ ->
            journal?.value?.sandboxRecords().orEmpty().distinctBy { it.commonDirectory }.mapNotNull { record ->
                val project = available.firstOrNull { it.ref == record.task.project } ?: return@mapNotNull null
                ProjectBuildPool(project.ref.value, project.name, projects.get(record.commonDirectory).poolSize)
            }
        }
    }

    private suspend fun repository(project: String): String = checkNotNull(
        journal?.value?.sandboxRecords()?.firstOrNull { it.task.project.value == project }?.commonDirectory,
    ) { "ProjectUnavailable" }

    override suspend fun setPool(project: String, size: Int) {
        checkNotNull(
            projects,
        ).setPoolSize(repository(project), size)
    }
    override suspend fun clearPool(project: String) {
        checkNotNull(projects).requestClear(repository(project))
    }
    override fun pauseTimeout(): Flow<Long> = projects?.timeoutChanges() ?: flowOf(DEFAULT_SANDBOX_PAUSE_MILLIS)
    override suspend fun setPauseTimeout(millis: Long) {
        checkNotNull(projects).setPauseTimeout(millis)
    }
}
