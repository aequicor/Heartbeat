package io.aequicor.heartbeat.feature.autocontinue.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.impl.domain.AutoContinueStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Settings and chat modes in the profile key-value store: one JSON value each. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueAutoContinueStore(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : AutoContinueStore {

    private val log = Log.tag("KeyValueAutoContinueStore")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun settings(): AutoContinueSettings = store.get(SETTINGS) ?: AutoContinueSettings()

    override suspend fun modes(): Map<String, AutoContinueMode> = store.get(MODES).orEmpty()

    override suspend fun saveSettings(settings: AutoContinueSettings) {
        log.d { "write auto-continue settings" }
        store.set(SETTINGS, settings)
    }

    override suspend fun saveMode(chat: String, mode: AutoContinueMode?) {
        val modes = modes()
        val updated = if (mode == null) modes - chat else modes + (chat to mode)
        log.d { "write auto-continue mode of $chat: ${mode ?: "removed"}" }
        store.set(MODES, updated)
    }

    private companion object {
        val SPEC = KeyValueSpec("auto_continue")
        val SETTINGS = jsonKey("settings", AutoContinueSettings.serializer())
        val MODES = jsonKey("modes", MapSerializer(String.serializer(), AutoContinueMode.serializer()))
    }
}
