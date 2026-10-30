package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeInstaller
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Profile preparation over the device-wide managed store; inspection never recursively prepares a runtime. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class ManagedEngineRuntimeInstaller(
    private val installs: ManagedInstallStore,
    private val feeds: ReleaseFeeds,
) : EngineRuntimeInstaller {
    private val mutex = Mutex()

    override suspend fun find(engine: EngineId, version: String, target: String?): ManagedInstall? =
        installs.retained(engine, version, target)

    override suspend fun prepare(
        engine: EngineId,
        version: String,
        manager: EngineManager,
        target: String?,
        progress: suspend (EnginePreparation) -> Unit,
    ): ManagedInstall = mutex.withLock {
        find(engine, version, target)?.let { retained ->
            if (isValid(manager, retained, version)) return@withLock retained
            // A damaged retained copy is never executed; a newly verified immutable copy replaces it.
        }
        val plan = withTimeout(LOOKUP_TIMEOUT_MS) { manager.resolveRelease(feeds) }
        if (plan.version != version || plan.target != target) throw installFailure(InstallFailureReason.VersionMismatch)
        val staged = installs.stage(engine, plan.copy(isRetainedForRollback = true)) { step ->
            progress(
                when (step) {
                    is InstallStep.Downloading -> EnginePreparation.Downloading(step.bytes, step.total)
                    InstallStep.Verified, InstallStep.Unpacking -> EnginePreparation.Installing
                },
            )
        }
        var isActivated = false
        try {
            progress(EnginePreparation.Installing)
            if (!isValid(manager, staged.candidate, version)) {
                throw installFailure(InstallFailureReason.VersionMismatch)
            }
            withContext(NonCancellable) {
                installs.activate(staged).also { isActivated = true }
            }
        } finally {
            if (!isActivated) withContext(NonCancellable) { installs.discard(staged) }
        }
    }

    private suspend fun isValid(manager: EngineManager, candidate: ManagedInstall, version: String): Boolean {
        val inspected = withTimeout(CHECK_TIMEOUT_MS) {
            manager.inspect(LaunchContext(managed = candidate, isInstallCandidate = true))
        }
        return inspected.isRunnable && inspected.source == InstallSource.Managed && inspected.version == version
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 30_000L
        const val CHECK_TIMEOUT_MS = 30_000L
    }
}
