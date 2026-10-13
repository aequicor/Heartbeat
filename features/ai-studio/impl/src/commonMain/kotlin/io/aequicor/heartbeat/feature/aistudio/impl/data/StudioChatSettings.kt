package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime

/** Run settings of a chat's next turn started outside the screen. */
internal fun interface StudioChatSettings {
    /** The chat's confirmed model and approval over the studio defaults. */
    suspend fun settings(chatId: String): RunSettings
}

/** [StudioChatSettings] from the studio's chat records, the way scheduled turns are configured. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredStudioChatSettings(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val runtime: StudioRuntime,
) : StudioChatSettings {
    override suspend fun settings(chatId: String): RunSettings {
        val record = checkNotNull(stores.keyValue(ChatSpec).get(ChatsKey).orEmpty().firstOrNull { it.id == chatId }) {
            "Unknown studio conversation"
        }
        return hostRunSettings(record, runtime.state.value.configurations[chatId]?.applied, runtime.defaults())
    }
}
