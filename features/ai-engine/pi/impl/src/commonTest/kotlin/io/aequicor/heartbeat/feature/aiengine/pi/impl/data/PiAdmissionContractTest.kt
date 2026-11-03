package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlin.test.Test
import kotlin.test.assertTrue

class PiAdmissionContractTest {
    @Test
    fun `Pi discovery requires admission before any adapter process is started`() {
        val adapter = object : PiAdapter {
            override suspend fun checkRequirements(): EngineAvailability = error("unexpected probe")
            override fun accepts(source: AuthSource, context: EngineContext): Boolean = error("unexpected route")
            override fun authContext(context: EngineContext): AuthContextKey = error("unexpected route")
            override suspend fun bind(binding: EngineBindingId, source: AuthSource): Unit = error("unexpected bind")
            override suspend fun unbind(binding: EngineBindingId): Unit = error("unexpected unbind")
            override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> =
                error("unexpected discovery")
            override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = error("unexpected runtime")
            override suspend fun configureWorkspace(workspace: WorkspaceRef, directory: String): Unit =
                error("unexpected workspace")
        }
        assertTrue(adapter.isAdmittedRuntimeRequiredForModels)
    }
}
