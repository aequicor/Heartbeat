package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitching
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Profile observations shared by local and remote screens, independent of a visible pane. */
internal fun observeStudioRuntime(
    scope: CoroutineScope,
    toggles: FeatureToggles,
    usage: EngineStudioUsage,
    store: KeyValueStore,
    mutableState: MutableStateFlow<StudioRuntimeState>,
) {
    val log = Log.tag("StudioRuntimeObservations")
    scope.launch {
        toggles.observe(StudioEngineSwitching).collect { enabled ->
            mutableState.update { it.copy(isEngineSwitchingAvailable = enabled) }
        }
    }
    scope.launch {
        usage.state.collect { snapshot ->
            log.v { "Update studio usage snapshot" }
            mutableState.update { it.copy(contexts = snapshot.contexts, providerUsage = snapshot.providers) }
        }
    }
    scope.launch {
        store.observe(ChatsKey).collect { records ->
            log.v { "Restore confirmed configurations count=${records.orEmpty().size}" }
            mutableState.update { runtime ->
                val restored = records.orEmpty().mapNotNull { record ->
                    val saved = record.configuration
                    saved?.let { record.id to StudioSessionConfiguration(it) }
                }.toMap()
                runtime.copy(configurations = restored + runtime.configurations)
            }
        }
    }
}

/** Reconciles provisioning before delivering durable checkout actions. */
internal fun CoroutineScope.observeStudioWorktrees(
    worktrees: StudioWorktrees,
    store: KeyValueStore,
    lock: kotlinx.coroutines.sync.Mutex,
    conversations: StudioConversationCreation,
    onTask: suspend (io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask) -> Unit,
) {
    launch {
        worktrees.tasks().collect { tasks ->
            lock.withLock {
                val saved = store.get(ChatsKey).orEmpty()
                val restored = conversations.recovered(saved, tasks)
                if (restored != saved) store.set(ChatsKey, restored)
            }
            tasks.values.forEach { onTask(it) }
        }
    }
}
