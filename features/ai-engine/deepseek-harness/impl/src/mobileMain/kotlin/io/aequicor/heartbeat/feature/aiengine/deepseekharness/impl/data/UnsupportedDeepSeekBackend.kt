package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekAuthentication
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekLogin
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekBackend
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

@ContributesBinding(ProfileScope::class, binding = binding<DeepSeekBackend>())
@ContributesBinding(ProfileScope::class, binding = binding<DeepSeekAuthentication>())
@Inject
internal class UnsupportedDeepSeekBackend : DeepSeekBackend {
    override suspend fun checkRequirements(): EngineAvailability = EngineAvailability.UnsupportedPlatform
    override suspend fun inspect(): DeepSeekLogin = unsupported()
    override fun accepts(source: AuthSource, context: EngineContext): Boolean = false
    override fun authContext(context: EngineContext) = DeepSeekEngine.AuthContext
    override suspend fun bind(binding: EngineBindingId, source: AuthSource): Unit = unsupported()
    override suspend fun unbind(binding: EngineBindingId): Unit = unsupported()
    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> = unsupported()
    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = unsupported()
    override suspend fun session(ref: SessionRef): EngineSession = unsupported()

    private fun unsupported(): Nothing = throw EngineException(
        EngineFailure.Engine(EngineFailureReason.UnsupportedCapability),
    )
}
