package io.aequicor.heartbeat.feature.worktreemode.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** One project's persistent build pool; [id] is opaque to the settings screen. */
internal data class ProjectBuildPool(val id: String, val name: String, val size: Int)

/** Machine-wide limit of parallel builds as the settings screen manages it. */
internal interface BuildLimits {
    /** Parallel standard builds the automatic CPU and memory estimate allows. */
    val automaticBuilds: Int

    /** The user limit and its changes; null is the automatic estimate. */
    fun observe(): Flow<Int?>

    /** Stores a user limit, or returns to the automatic estimate with null. */
    suspend fun set(parallelBuilds: Int?)

    fun projects(): Flow<List<ProjectBuildPool>> = flowOf(emptyList())
    suspend fun setPool(project: String, size: Int) {
        error("ProjectPoolsUnavailable")
    }
    suspend fun clearPool(project: String) {
        error("ProjectPoolsUnavailable")
    }
    fun pauseTimeout(): Flow<Long> = flowOf(DEFAULT_SANDBOX_PAUSE_MILLIS)
    suspend fun setPauseTimeout(millis: Long) {
        error("SandboxSettingsUnavailable")
    }
}

internal const val DEFAULT_SANDBOX_PAUSE_MILLIS = 240_000L
