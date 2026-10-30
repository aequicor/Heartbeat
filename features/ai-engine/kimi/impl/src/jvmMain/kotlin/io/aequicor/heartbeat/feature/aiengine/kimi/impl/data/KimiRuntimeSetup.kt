package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparationFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeInstaller
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** First-use preparation shared by CLI login, discovery and sessions, including installations without a Kimi seed. */
@Inject
@SingleIn(ProfileScope::class)
internal class KimiRuntimeSetup(
    private val installer: EngineRuntimeInstaller,
    private val dispatchers: DispatcherProvider,
    private val resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
) {
    private val log = Log.tag("KimiRuntimeSetup")
    private val mutex = Mutex()
    private val status = MutableStateFlow<EnginePreparation>(EnginePreparation.Idle)
    val preparation = status.asStateFlow()

    /** Read-only resolution: a staged candidate wins; otherwise the retained adapter pin, then the installer seed. */
    suspend fun locate(launch: LaunchContext = LaunchContext()): Installation {
        val managed = if (launch.isInstallCandidate) {
            requireNotNull(launch.managed)
        } else {
            installer.find(KimiEngine.Id, KIMI_RUNTIME_VERSION, kimiRuntimeTarget())
                ?: launch.managed?.takeIf { it.version == KIMI_RUNTIME_VERSION }
        }
        val executable = withContext(dispatchers.io) {
            try {
                kimiControlledExecutable(resourcesDirectory = resourcesDirectory, managed = managed)
            } catch (error: IOException) {
                log.w(error.redactedFailure()) { "Kimi runtime could not be read" }
                throw EngineException(
                    io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure.Engine(
                        io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason.RequirementsNotMet,
                    ),
                )
            }
        }
        return Installation(
            if (managed == null) InstallSource.Bundled else InstallSource.Managed,
            KIMI_RUNTIME_VERSION,
            executable,
        )
    }

    suspend fun prepare(manager: EngineManager) = mutex.withLock {
        try {
            val current = try {
                locate()
            } catch (error: EngineException) {
                log.w(error) { "Pinned Kimi runtime needs preparation" }
                null
            }
            if (current == null) {
                installer.prepare(
                    KimiEngine.Id,
                    KIMI_RUNTIME_VERSION,
                    manager,
                    target = kimiRuntimeTarget(),
                ) { status.value = it }
            }
            status.value = EnginePreparation.Ready
        } catch (error: CancellationException) {
            status.value = EnginePreparation.Idle
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Kimi runtime preparation failed" }
            status.value = EnginePreparation.Failed(EnginePreparationFailure.Unavailable)
            throw error
        }
    }
}
