package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeInstaller
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
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
    private val registry: EngineRegistry,
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
            if (isValid(engine, manager, retained, version)) return@withLock retained
            // An unverified retained copy is not reused by preparation; try the staged release instead.
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
            if (!isValid(engine, manager, staged.candidate, version)) {
                throw installFailure(InstallFailureReason.VersionMismatch)
            }
            withContext(NonCancellable) {
                installs.activate(staged).also { isActivated = true }
            }
        } finally {
            if (!isActivated) withContext(NonCancellable) { installs.discard(staged) }
        }
    }

    private suspend fun isValid(
        engine: EngineId,
        manager: EngineManager,
        candidate: ManagedInstall,
        version: String,
    ): Boolean {
        val policy = registry.require(engine).factory.value.runtimeRequirements ?: return false
        if (policy.engine != engine || candidate.version != version) return false
        val installation = Installation(InstallSource.Managed, candidate.version, candidate.executable)
        val launch = LaunchContext(managed = candidate, isInstallCandidate = true)
        val report = withTimeout(CHECK_TIMEOUT_MS) { manager.inspectCompatibility(installation, launch) }
            ?: return false
        val identity = report.evidence.identity
        return identity.engine == engine && identity.runtime == version &&
            assessEngineCompatibility(policy, report.evidence).status == Compatibility.Verified
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 30_000L
        const val CHECK_TIMEOUT_MS = 30_000L
    }
}
