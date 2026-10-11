package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Null means no loaded project catalog, including after the enabled observer has stopped. A failed catalog
 * revokes the proof and is collected again with a growing delay, so project harnesses recover without a toggle.
 * The delay and the warning reset only after a collection stayed healthy for [PROJECT_STABLE_PERIOD]; a source
 * that emits and then fails right away keeps backing off and is reported once per failure streak.
 */
internal class HarnessProjectSnapshots(private val time: TimeSource = TimeSource.Monotonic) {
    private val snapshot = MutableStateFlow<Set<WorkspaceRef>?>(null)
    val projects = snapshot.asStateFlow()
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    fun observe(scope: CoroutineScope, source: Flow<List<LocalWorkspace>>) {
        log.v { "observe trusted local project catalog" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                var failures = 0
                while (true) {
                    failures = attempt(source, failures) ?: break
                    delay(PROJECT_RETRY_BASE * (1 shl minOf(failures - 1, PROJECT_RETRY_STEPS)))
                }
            } finally {
                snapshot.value = null
            }
        }
    }

    /**
     * One collection. Null when the source completed; otherwise the length of the current failure streak, which
     * restarts after a collection that stayed healthy for [PROJECT_STABLE_PERIOD]. A streak is warned about once.
     */
    private suspend fun attempt(source: Flow<List<LocalWorkspace>>, failures: Int): Int? {
        val started = time.markNow()
        return try {
            source.collect { workspaces ->
                snapshot.value = workspaces.mapTo(linkedSetOf()) { it.ref }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            snapshot.value = null
            val streak = if (started.elapsedNow() >= PROJECT_STABLE_PERIOD) 0 else failures
            if (streak == 0) {
                log.w(IllegalStateException("Harness project source failed", error)) {
                    "Project observation failed; retrying"
                }
            } else {
                log.v { "project observation is still failing" }
            }
            streak + 1
        }
    }
}

private val PROJECT_RETRY_BASE = 1.seconds

/** A collection that lasted this long without failing counts as recovered. */
private val PROJECT_STABLE_PERIOD = 5.minutes

/** The retry delay doubles up to 64 seconds. */
private const val PROJECT_RETRY_STEPS = 6
