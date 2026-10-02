package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StagedInstall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hosts that cannot run downloaded executables (Android, iOS); the Desktop store has a higher priority. */
@Inject
@ContributesBinding(AppScope::class, priority = 0)
internal class UnsupportedManagedInstallStore : ManagedInstallStore {
    private val log = Log.tag("UnsupportedManagedInstallStore")
    private val installs = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())

    override val state: StateFlow<Map<EngineId, ManagedInstall>> = installs.asStateFlow()

    override suspend fun refresh() = Unit

    override suspend fun stage(
        engine: EngineId,
        plan: InstallPlan,
        progress: suspend (InstallStep) -> Unit,
    ): StagedInstall = unsupported()

    override suspend fun activate(staged: StagedInstall): ManagedInstall = unsupported()

    override suspend fun discard(staged: StagedInstall) = Unit

    override suspend fun uninstall(engine: EngineId) = Unit

    private fun unsupported(): Nothing {
        log.w { "managed installs are not supported on this platform" }
        throw installFailure(InstallFailureReason.Unsupported)
    }
}
