package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Null means no loaded project catalog, including after the enabled observer has stopped. */
internal class HarnessProjectSnapshots {
    private val snapshot = MutableStateFlow<Set<WorkspaceRef>?>(null)
    val projects = snapshot.asStateFlow()
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    fun observe(scope: CoroutineScope, source: Flow<List<LocalWorkspace>>) {
        log.v { "observe trusted local project catalog" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.catch { error ->
                    if (error is CancellationException || error !is Exception) throw error
                    snapshot.value = null
                    log.w(IllegalStateException("Harness project source failed")) {
                        "Project observation failed (${error::class.simpleName ?: "Exception"})"
                    }
                }.collect { snapshot.value = it.mapTo(linkedSetOf()) { workspace -> workspace.ref } }
            } finally {
                snapshot.value = null
            }
        }
    }
}
