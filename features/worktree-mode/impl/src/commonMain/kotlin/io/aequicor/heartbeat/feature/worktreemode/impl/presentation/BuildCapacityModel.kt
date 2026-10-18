package io.aequicor.heartbeat.feature.worktreemode.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.BuildLimits
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.ProjectBuildPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/** The parallel build limit as the screen shows it; null [limit] is the automatic estimate. */
@Immutable
internal data class BuildCapacityScreenState(
    val automaticBuilds: Int = 1,
    val limit: Int? = null,
    val isLoaded: Boolean = false,
    val isSaveFailed: Boolean = false,
    val projects: List<ProjectBuildPool> = emptyList(),
    val pauseMinutes: Int = 4,
    val isSandboxLoaded: Boolean = false,
) : MVIState

/** Changes offered by the build settings. */
internal sealed interface BuildCapacityScreenIntent : MVIIntent {
    /** A fixed number of parallel standard builds, or null for the automatic estimate. */
    data class SetLimit(val parallelBuilds: Int?) : BuildCapacityScreenIntent
    data class SetPool(val project: String, val size: Int) : BuildCapacityScreenIntent
    data class ClearPool(val project: String) : BuildCapacityScreenIntent
    data class SetPauseMinutes(val minutes: Int) : BuildCapacityScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface BuildCapacityScreenAction : MVIAction

/** Limits the screen offers besides the automatic estimate. */
internal val ParallelBuildChoices = listOf(1, 2, 3, 4, 6, 8)

/** Reflects the stored limit while the screen is subscribed; a change is written straight to the setting. */
internal class BuildCapacityModel(limits: BuildLimits, factory: HeartbeatStoreFactory, scope: CoroutineScope) {
    private val log = Log.tag("BuildCapacityModel")

    /** The settings store, retained for the lifetime of this screen. */
    val store = factory.create<BuildCapacityScreenState, BuildCapacityScreenIntent, BuildCapacityScreenAction>(
        "BuildCapacity",
        BuildCapacityScreenState(automaticBuilds = limits.automaticBuilds),
        onError = { this },
    ) {
        whileSubscribed {
            launch {
                combine(limits.projects(), limits.pauseTimeout()) { pools, millis -> pools to millis }
                    .collect { (pools, millis) ->
                        updateState {
                            copy(
                                projects = pools,
                                pauseMinutes = (millis / 60_000L).toInt(),
                                isSandboxLoaded = true,
                            )
                        }
                    }
            }
            limits.observe().collect { limit -> updateState { copy(limit = limit, isLoaded = true) } }
        }
        reduce { intent ->
            try {
                when (intent) {
                    is BuildCapacityScreenIntent.SetLimit -> limits.set(intent.parallelBuilds)
                    is BuildCapacityScreenIntent.SetPool -> limits.setPool(intent.project, intent.size)
                    is BuildCapacityScreenIntent.ClearPool -> limits.clearPool(intent.project)
                    is BuildCapacityScreenIntent.SetPauseMinutes -> limits.setPauseTimeout(intent.minutes * 60_000L)
                }
                updateState { copy(isSaveFailed = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Build capacity limit was not saved" }
                updateState { copy(isSaveFailed = true) }
            }
        }
    }

    init {
        store.start(scope)
    }
}
