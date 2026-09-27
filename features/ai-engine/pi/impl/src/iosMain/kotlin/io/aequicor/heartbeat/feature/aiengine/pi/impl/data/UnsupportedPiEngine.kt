package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine

@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedPiEngine : PiEngine {
    override suspend fun checkRequirements(): EngineAvailability = EngineAvailability.UnsupportedPlatform
    override fun accepts(source: AuthSource, context: EngineContext): Boolean = false
    override fun authContext(context: EngineContext): AuthContextKey = AuthContextKey("pi.unsupported")
    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> = unsupported()
    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = unsupported()
    override suspend fun configure(binding: EngineBindingId, source: AuthSource.ManagedKey): Unit = unsupported()
    override suspend fun configureWorkspace(workspace: WorkspaceRef, directory: String): Unit = unsupported()

    private fun unsupported(): Nothing = piFailure(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
}
