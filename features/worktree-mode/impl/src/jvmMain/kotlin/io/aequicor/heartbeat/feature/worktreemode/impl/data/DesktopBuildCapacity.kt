package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory

/**
 * Estimates the build budget from this machine's logical processors and physical memory; a user limit from
 * [BuildCapacitySettings] replaces the estimate as soon as it is stored.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopBuildCapacity(
    @ForScope(AppScope::class) app: ScopeHandle,
    settings: BuildCapacitySettings,
) : BuildCapacity {
    private val log = Log.tag("DesktopBuildCapacity")

    @Volatile
    private var limit: Int? = null

    override val automatic: BuildBudget by lazy {
        val memory = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.takeIf { it > 0 }?.let { it / BYTES_PER_MB }
        BuildBudget.auto(Runtime.getRuntime().availableProcessors(), memory)
    }

    init {
        app.coroutineScope.launch {
            try {
                settings.observe().collect { limit = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Build capacity limit unavailable; the automatic estimate stays in effect" }
            }
        }
    }

    override fun budget(): BuildBudget = limit?.let(BuildBudget::parallel) ?: automatic

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}
