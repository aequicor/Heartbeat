package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

private val PreferencesSpec = KeyValueSpec("ai_studio_preferences")
private val DefaultsKey = jsonKey("new_session", SavedStudioDefaults.serializer())

/** Only explicit start-page selections are stored; engine acknowledgements never change these defaults. */
@Serializable
private data class SavedStudioDefaults(val modelId: String, val approval: ApprovalMode)

/**
 * Keeps accepted preferences in memory even when disk IO fails. Writes and reads share a lock so reopening the
 * screen cannot read an older value while a write is in flight. The profile owns writes, not their screen waiters.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueStudioPreferences(
    @ForScope(ProfileScope::class) stores: DataStores,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : StudioPreferences {
    private val log = Log.tag("StudioPreferences")
    private val store = stores.keyValue(PreferencesSpec)
    private val lock = Mutex()
    private val revisions = mutableMapOf<String, Long>()
    private var isLoaded = false
    private var current: SavedStudioDefaults? = null

    override suspend fun load(fallback: RunSettings): RunSettings = lock.withLock {
        log.d { "Read start-page preferences" }
        if (!isLoaded) {
            try {
                current = store.get(DefaultsKey)
                isLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Could not read start-page preferences; using initial defaults" }
            }
        }
        current?.let { fallback.copy(modelId = it.modelId, approval = it.approval) } ?: fallback
    }

    override suspend fun save(settings: RunSettings, version: StudioSettingsVersion) {
        profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock {
                if (version.revision <= (revisions[version.writer] ?: -1)) {
                    log.d { "Skip stale start-page preferences revision=${version.revision}" }
                    return@withLock
                }
                val saved = SavedStudioDefaults(settings.modelId, settings.approval)
                revisions[version.writer] = version.revision
                current = saved
                isLoaded = true
                log.d { "Write start-page preferences revision=${version.revision}" }
                try {
                    store.set(DefaultsKey, saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(e) { "Could not save start-page preferences; keeping the current choice in memory" }
                    throw e
                }
            }
        }.await()
    }
}
