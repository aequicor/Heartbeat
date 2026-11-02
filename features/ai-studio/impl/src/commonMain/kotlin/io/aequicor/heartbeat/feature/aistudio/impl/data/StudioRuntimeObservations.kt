package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitching
import io.aequicor.heartbeat.feature.aistudio.api.StudioMessageQueueEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Clear only the completed conversation's transient execution and permission projection. */
internal fun StudioRuntimeState.withoutFinishedRun(id: String): StudioRuntimeState = copy(
    running = running - id,
    runStartedAt = runStartedAt - id,
    stopFailures = stopFailures - id,
    uncancellable = uncancellable - id,
    steerable = steerable - id,
    permissions = permissions.filterNot { it.sessionId == id },
)

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

/** Queue projection is owned by the profile and shared by every pane observing the same chat. */
internal fun observeStudioMessageQueue(
    scope: CoroutineScope,
    queue: StudioQueuePort,
    toggles: FeatureToggles,
    state: MutableStateFlow<StudioRuntimeState>,
) {
    val log = Log.tag("StudioQueueProjection")
    scope.launch {
        queue.state.collect { queues ->
            log.v { "Project profile queue snapshot" }
            state.update { it.copy(messageQueues = queues) }
        }
    }
    scope.launch {
        queue.notices.collect { notices ->
            log.v { "Project transient queue feedback" }
            state.update { it.copy(queueNotices = notices) }
        }
    }
    scope.launch {
        toggles.observe(StudioMessageQueueEnabled).collect { enabled ->
            log.v { "Message queue availability changed enabled=$enabled" }
            state.update { it.copy(isMessageQueueEnabled = enabled) }
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

/** Resolves a profile-owned native identity, including carried sessions. Caller serializes record access. */
internal suspend fun KeyValueStore.studioChatOf(ref: SessionRef): String? =
    get(ChatsKey).orEmpty().firstOrNull { record -> record.owns(ref) }?.id
