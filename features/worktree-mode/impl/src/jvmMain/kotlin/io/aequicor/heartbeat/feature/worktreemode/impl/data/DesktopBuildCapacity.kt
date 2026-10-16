package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildQueueEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.management.ManagementFactory

/**
 * Estimates the build budget from this machine's logical processors and physical memory; a user limit from
 * [BuildCapacitySettings] replaces the estimate. Without [WorktreeBuildQueueEnabled] the budget is unlimited.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopBuildCapacity(
    @ForScope(AppScope::class) app: ScopeHandle,
    settings: BuildCapacitySettings,
    toggles: FeatureToggles,
) : BuildCapacity {
    private val log = Log.tag("DesktopBuildCapacity")
    private val state = MutableStateFlow<Policy?>(null)

    override val automatic: BuildBudget by lazy {
        val memory = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.takeIf { it > 0 }?.let { it / BYTES_PER_MB }
        BuildBudget.auto(Runtime.getRuntime().availableProcessors(), memory)
    }

    init {
        app.coroutineScope.launch {
            try {
                combine(toggles.observe(WorktreeBuildQueueEnabled), settings.observe(), ::Policy)
                    .collect { state.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Build capacity settings unavailable; the automatic estimate stays in effect" }
            }
        }
    }

    /** Waits briefly for the stored policy after start, so a lower user limit applies to the very first builds. */
    override suspend fun budget(): BuildBudget {
        val policy = state.value ?: withTimeoutOrNull(POLICY_WAIT_MILLIS) { state.filterNotNull().first() }
        return when {
            policy == null -> automatic
            !policy.isQueueEnabled -> BuildBudget.Unlimited
            else -> policy.limit?.let(BuildBudget::parallel) ?: automatic
        }
    }

    override fun changes(): Flow<Unit> = state.filterNotNull().map { }

    private data class Policy(val isQueueEnabled: Boolean, val limit: Int?)

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
        const val POLICY_WAIT_MILLIS = 2_000L
    }
}
