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
import kotlin.time.Duration.Companion.seconds

/**
 * Null means no loaded project catalog, including after the enabled observer has stopped. A failed catalog
 * revokes the proof and is collected again with a growing delay, so project harnesses recover without a toggle.
 */
internal class HarnessProjectSnapshots {
    private val snapshot = MutableStateFlow<Set<WorkspaceRef>?>(null)
    val projects = snapshot.asStateFlow()
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    fun observe(scope: CoroutineScope, source: Flow<List<LocalWorkspace>>) {
        log.v { "observe trusted local project catalog" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                var failures = 0
                do {
                    val isCompleted = try {
                        source.collect { workspaces ->
                            failures = 0
                            snapshot.value = workspaces.mapTo(linkedSetOf()) { it.ref }
                        }
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        snapshot.value = null
                        log.w(IllegalStateException("Harness project source failed", error)) {
                            "Project observation failed; retrying"
                        }
                        delay(PROJECT_RETRY_BASE * (1 shl minOf(failures++, PROJECT_RETRY_STEPS)))
                        false
                    }
                } while (!isCompleted)
            } finally {
                snapshot.value = null
            }
        }
    }
}

private val PROJECT_RETRY_BASE = 1.seconds

/** The retry delay doubles up to 64 seconds. */
private const val PROJECT_RETRY_STEPS = 6
