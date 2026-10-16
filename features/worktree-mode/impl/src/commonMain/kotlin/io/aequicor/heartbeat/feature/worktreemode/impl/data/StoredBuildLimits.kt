package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.BuildLimits
import kotlinx.coroutines.flow.Flow

/** The stored user limit next to this machine's automatic estimate. */
@Inject
@ContributesBinding(AppScope::class)
internal class StoredBuildLimits(private val settings: BuildCapacitySettings, private val capacity: BuildCapacity) :
    BuildLimits {
    override val automaticBuilds: Int get() = capacity.automatic.parallelBuilds

    override fun observe(): Flow<Int?> = settings.observe()

    override suspend fun set(parallelBuilds: Int?) = settings.set(parallelBuilds)
}
