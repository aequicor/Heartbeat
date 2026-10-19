package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorage
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorageSettings
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBindingStorage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes location changes with physical project IO. A saved App choice fences every later access. */
@SingleIn(ProfileScope::class)
@Inject
internal class ConfigStoragePolicy(private val storage: ConfigBindingStorage) {
    private val mutex = Mutex()

    /** Loads the durable policy, never replacing unreadable settings with defaults. */
    suspend fun load(): ConfigStorageSettings = mutex.withLock { storage.loadSettings() }

    /** Waits for previous IO before making the new choice durable. */
    suspend fun save(settings: ConfigStorageSettings) = mutex.withLock { storage.saveSettings(settings) }

    /** Runs [operation] only while [project] uses its tree; a disabled project returns null without IO. */
    suspend fun <T> access(project: WorkspaceRef, operation: suspend () -> T): T? = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (storage.loadSettings().location(project) == ConfigStorage.Project) operation() else null
    }
}
