package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnginePreferences
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEnginePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Engine management choices held in memory. */
internal class MemoryEnginePreferences(initial: ProfileEnginePreferences = ProfileEnginePreferences()) :
    EnginePreferences {
    val current = MutableStateFlow(initial)

    override fun observe(): Flow<ProfileEnginePreferences> = current

    override suspend fun load(): ProfileEnginePreferences = current.value

    override suspend fun update(
        change: (ProfileEnginePreferences) -> ProfileEnginePreferences,
    ): ProfileEnginePreferences = change(current.value).also { current.value = it }
}
