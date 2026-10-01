package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUseSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Stores the computer use settings in the profile key-value store. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileComputerUsePreferences(
    @ForScope(ProfileScope::class) stores: DataStores,
) : ComputerUsePreferences {
    private val log = Log.tag("ComputerUsePreferences")
    private val store = stores.keyValue(KeyValueSpec(STORE_NAME, areValuesLogged = true))

    override suspend fun read(): ComputerUseSettings {
        val preset = store.get(PresetKey) ?: ComputerUseSettings.DEFAULT_PRESET
        val isCursorIncluded = store.get(CursorKey) ?: true
        return ComputerUseSettings(preset, isCursorIncluded)
    }

    override fun observe(): Flow<ComputerUseSettings> = store.observe(PresetKey).map { preset ->
        ComputerUseSettings(preset ?: ComputerUseSettings.DEFAULT_PRESET, store.get(CursorKey) ?: true)
    }

    override suspend fun setPreset(name: String): Boolean {
        if (CapturePresets.byName(name) == null) {
            log.w { "unknown frame preset refused" }
            return false
        }
        store.set(PresetKey, name)
        log.i { "frame preset changed preset=$name" }
        return true
    }

    override suspend fun setCursorIncluded(isIncluded: Boolean) {
        store.set(CursorKey, isIncluded)
        log.i { "cursor inclusion changed included=$isIncluded" }
    }

    private companion object {
        const val STORE_NAME = "computer_use"
        val PresetKey = stringKey("frame_preset")
        val CursorKey = booleanKey("include_cursor")
    }
}
