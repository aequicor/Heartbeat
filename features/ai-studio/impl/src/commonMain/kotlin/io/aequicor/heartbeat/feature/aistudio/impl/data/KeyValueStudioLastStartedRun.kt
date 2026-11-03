package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioLastStartedRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val LastStartedKey = jsonKey("last_started", StudioSessionSettings.serializer())

/**
 * One atomic in-memory receipt is published before any persistence. A single profile writer coalesces queued
 * receipts and serializes disk writes; a slow earlier write cannot overwrite a newer accepted run on disk.
 * Legacy start-page choices are never migrated into evidence of a real submission. Persistence is best-effort
 * until the profile closes: owner shutdown cancels queued/in-flight IO, just as for other profile preferences.
 * An in-memory receipt is not a claim that disk persistence completed; no write outlives its storage owner.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueStudioLastStartedRun(
    @ForScope(ProfileScope::class) stores: DataStores,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : StudioLastStartedRun {
    private val log = Log.tag("StudioLastStartedRun")
    private val store = stores.keyValue(PreferencesSpec)
    private val current = MutableStateFlow<Receipt?>(null)

    init {
        profile.coroutineScope.launch {
            current.filterNotNull().collect { receipt ->
                try {
                    store.set(LastStartedKey, receipt.settings)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error) { "Accepted chat defaults could not be saved; retaining the in-memory receipt" }
                }
            }
        }
    }

    override fun observe(): Flow<StudioSessionSettings?> = store.observe(LastStartedKey)
        .catch { error ->
            if (error is CancellationException) throw error
            log.e(error) { "Accepted chat defaults could not be loaded" }
            emit(null)
        }
        .combine(current) { saved, captured -> captured?.settings ?: saved }

    override fun capture(settings: StudioSessionSettings) {
        check(!profile.isClosed) { "The profile is closed" }
        current.update { Receipt((it?.ordinal ?: 0) + 1, settings) }
        log.d { "Captured accepted chat defaults" }
    }

    private data class Receipt(val ordinal: Long, val settings: StudioSessionSettings)
}
