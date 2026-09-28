package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability

@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedCodexTransport : CodexTransport {
    override suspend fun available(): EngineAvailability = EngineAvailability.UnsupportedPlatform
    override suspend fun open(): CodexWire = unsupported()
}
