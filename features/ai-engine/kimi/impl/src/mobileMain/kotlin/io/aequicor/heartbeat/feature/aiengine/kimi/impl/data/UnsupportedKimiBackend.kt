package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain.KimiBackend

@ContributesBinding(ProfileScope::class, binding = dev.zacsweers.metro.binding<KimiBackend>())
@Inject
internal class UnsupportedKimiBackend : KimiBackend {
    override suspend fun checkRequirements(): EngineAvailability = EngineAvailability.UnsupportedPlatform
    override fun accepts(source: AuthSource, context: EngineContext): Boolean = false
    override fun authContext(context: EngineContext) = KimiEngine.AuthContext
    override suspend fun bind(binding: EngineBindingId, source: AuthSource): Unit = unsupported()
    override suspend fun unbind(binding: EngineBindingId): Unit = unsupported()
    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> = unsupported()
    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = unsupported()
    override suspend fun session(ref: SessionRef): EngineSession = unsupported()

    private fun unsupported(): Nothing = throw EngineException(
        EngineFailure.Engine(EngineFailureReason.UnsupportedCapability),
    )
}
