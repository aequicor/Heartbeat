package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityStore

/** Settings-machine adapter; persistence and the effective execution mode belong to the profile facade. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class KeyValueStudioSecurityStore(private val settings: AgentSecuritySettings) : StudioSecurityStore {
    override val levels = settings.modes

    override suspend fun load(): StudioSecurityLevel? = settings.load()

    override suspend fun save(level: StudioSecurityLevel) = settings.save(level)
}
