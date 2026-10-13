package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserWorkspaceStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserWorkspace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val WorkspaceSpec = KeyValueSpec("browser_workspace")
private val WorkspaceKey = jsonKey("tabs", SavedBrowserWorkspace.serializer())

/** Serializes durable snapshots and skips loading/history-only updates; URLs are never logged by the store. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueBrowserWorkspaceStorage(
    @ForScope(ProfileScope::class) stores: DataStores,
) : BrowserWorkspaceStorage {
    private val log = Log.tag("BrowserWorkspaceStorage")
    private val store = stores.keyValue(WorkspaceSpec)
    private val lock = Mutex()
    private var saved: SavedBrowserWorkspace? = null

    override suspend fun load(): SavedBrowserWorkspace = lock.withLock {
        log.v { "Loading browser workspace metadata" }
        (store.get(WorkspaceKey) ?: SavedBrowserWorkspace()).validated().also { saved = it }
    }

    @HighFrequency
    override suspend fun save(workspace: SavedBrowserWorkspace) = lock.withLock {
        if (workspace != saved) {
            log.v { "Saving changed browser workspace metadata" }
            store.set(WorkspaceKey, workspace)
            saved = workspace
        }
    }
}
