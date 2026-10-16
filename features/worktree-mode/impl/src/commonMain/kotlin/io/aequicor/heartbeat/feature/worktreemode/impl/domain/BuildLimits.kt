package io.aequicor.heartbeat.feature.worktreemode.impl.domain

import kotlinx.coroutines.flow.Flow

/** Machine-wide limit of parallel builds as the settings screen manages it. */
internal interface BuildLimits {
    /** Parallel standard builds the automatic CPU and memory estimate allows. */
    val automaticBuilds: Int

    /** The user limit and its changes; null is the automatic estimate. */
    fun observe(): Flow<Int?>

    /** Stores a user limit, or returns to the automatic estimate with null. */
    suspend fun set(parallelBuilds: Int?)
}
