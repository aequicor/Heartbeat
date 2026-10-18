package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.datastore.longKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Shared by registrations and profiles of one canonical Git repository. */
@Serializable
internal data class ProjectBuildConfiguration(
    val poolSize: Int = 2,
    val plan: WorktreeBuildPlan? = null,
    val revision: Long = 0,
    val isClearing: Boolean = false,
)

/** Durable project manager configuration, independent of a chat and of its language/toolchain. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class StoredProjectBuildSettings(
    @ForScope(AppScope::class) stores: Lazy<DataStores>,
) : ProjectBuildSettings {
    private val store by lazy { stores.value.keyValue(SPEC) }
    private val mutex = Mutex()

    override suspend fun get(project: String): ProjectBuildConfiguration =
        store.get(PROJECTS).orEmpty()[project] ?: ProjectBuildConfiguration()

    override fun changes() = store.observe(PROJECTS).map { Unit }
    override fun timeoutChanges() = store.observe(TIMEOUT).map { it ?: DEFAULT_TIMEOUT }
    override suspend fun requestClear(project: String) = update(project) { it.copy(isClearing = true) }
    override suspend fun finishClear(project: String) = update(project) { it.copy(isClearing = false) }

    override suspend fun setPoolSize(project: String, size: Int) = update(project) {
        require(size in 1..BuildBudget.MAX_PARALLEL_BUILDS) { "InvalidPoolSize" }
        it.copy(poolSize = size)
    }

    override suspend fun configure(project: String, plan: WorktreeBuildPlan) = update(project) {
        if (it.plan == plan) it else it.copy(plan = plan, revision = it.revision + 1)
    }

    override suspend fun pauseTimeout(): Long = store.get(TIMEOUT) ?: DEFAULT_TIMEOUT

    override suspend fun setPauseTimeout(millis: Long) {
        require(millis > 0) { "InvalidSandboxPauseTimeout" }
        store.set(TIMEOUT, millis)
    }

    private suspend fun update(
        project: String,
        transform: (ProjectBuildConfiguration) -> ProjectBuildConfiguration,
    ): ProjectBuildConfiguration = mutex.withLock {
        val saved = store.get(PROJECTS).orEmpty()
        val next = transform(saved[project] ?: ProjectBuildConfiguration())
        store.set(PROJECTS, saved + (project to next))
        next
    }

    private companion object {
        const val DEFAULT_TIMEOUT = 240_000L
        val SPEC = KeyValueSpec("sandbox_project_builds", readPolicy = KeyValueReadPolicy.Strict)
        val PROJECTS = jsonKey<Map<String, ProjectBuildConfiguration>>(
            "projects",
            MapSerializer(String.serializer(), ProjectBuildConfiguration.serializer()),
        )
        val TIMEOUT = longKey("external_edit_timeout_millis")
    }
}

internal interface ProjectBuildSettings {
    suspend fun get(project: String): ProjectBuildConfiguration
    fun changes(): kotlinx.coroutines.flow.Flow<Unit>
    fun timeoutChanges(): kotlinx.coroutines.flow.Flow<Long>
    suspend fun requestClear(project: String): ProjectBuildConfiguration
    suspend fun finishClear(project: String): ProjectBuildConfiguration
    suspend fun setPoolSize(project: String, size: Int): ProjectBuildConfiguration
    suspend fun configure(project: String, plan: WorktreeBuildPlan): ProjectBuildConfiguration
    suspend fun pauseTimeout(): Long
    suspend fun setPauseTimeout(millis: Long)
}
