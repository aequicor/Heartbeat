package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.lang.management.ManagementFactory

/** Estimates the build budget from this machine's logical processors and physical memory. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopBuildCapacity : BuildCapacity {
    private val automatic: BuildBudget by lazy {
        val memory = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.takeIf { it > 0 }?.let { it / BYTES_PER_MB }
        BuildBudget.auto(Runtime.getRuntime().availableProcessors(), memory)
    }

    override fun budget(): BuildBudget = automatic

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}
