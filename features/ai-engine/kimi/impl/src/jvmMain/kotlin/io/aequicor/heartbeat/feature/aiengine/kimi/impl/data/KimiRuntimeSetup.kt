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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeInstaller
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** First-use preparation shared by CLI login, discovery and sessions, including installations without a Kimi seed. */
@SingleIn(ProfileScope::class)
internal class KimiRuntimeSetup internal constructor(
    private val installer: EngineRuntimeInstaller,
    private val dispatchers: DispatcherProvider,
    private val resourcesDirectory: String?,
    private val isTrusted: (KimiControlledRuntime) -> Boolean,
) {
    @Inject
    constructor(installer: EngineRuntimeInstaller, dispatchers: DispatcherProvider) : this(
        installer,
        dispatchers,
        System.getProperty("compose.application.resources.dir"),
        ::isKnownKimiRelease,
    )

    internal constructor(
        installer: EngineRuntimeInstaller,
        dispatchers: DispatcherProvider,
        resourcesDirectory: String?,
    ) : this(installer, dispatchers, resourcesDirectory, ::isKnownKimiRelease)

    private val log = Log.tag("KimiRuntimeSetup")
    private val mutex = Mutex()
    private val status = MutableStateFlow<EnginePreparation>(EnginePreparation.Idle)
    val preparation = status.asStateFlow()

    /** Read-only resolution: a staged candidate wins; otherwise the retained adapter pin, then the installer seed. */
    suspend fun locate(launch: LaunchContext = LaunchContext()): Installation {
        val (runtime, source) = locateControlled(launch)
        return Installation(source, KIMI_RUNTIME_VERSION, runtime.executable)
    }

    /** Release-pin preflight for process entry points; it does not establish the identity of executed bytes. */
    suspend fun locateRelease(launch: LaunchContext = LaunchContext()): Installation {
        val (runtime, source) = locateControlled(launch)
        if (!isTrusted(runtime)) {
            throw EngineException(
                io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure.Engine(
                    io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason.RequirementsNotMet,
                ),
            )
        }
        return Installation(source, KIMI_RUNTIME_VERSION, runtime.executable)
    }

    /** Revalidates the actual selected artifact and returns its digest without trusting a prior inspection. */
    suspend fun locateControlled(launch: LaunchContext): Pair<KimiControlledRuntime, InstallSource> {
        val managed = selectedManaged(launch)
        val runtime = withContext(dispatchers.io) {
            val context = currentCoroutineContext()
            try {
                kimiControlledRuntime(resourcesDirectory = resourcesDirectory, managed = managed) {
                    context.ensureActive()
                }
            } catch (error: IOException) {
                log.w(error.redactedFailure()) { "Kimi runtime could not be read" }
                throw EngineException(
                    io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure.Engine(
                        io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason.RequirementsNotMet,
                    ),
                )
            }
        }
        return runtime to if (managed == null) InstallSource.Bundled else InstallSource.Managed
    }

    private suspend fun selectedManaged(launch: LaunchContext): ManagedInstall? = if (launch.isInstallCandidate) {
        requireNotNull(launch.managed)
    } else if (launch.settings.runtime == RuntimePreference.Managed) {
        launch.managed ?: throw EngineException(
            io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure.Engine(
                io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason.RequirementsNotMet,
            ),
        )
    } else if (launch.settings.runtime == RuntimePreference.Bundled) {
        null
    } else if (launch.settings.runtime == RuntimePreference.Auto) {
        installer.find(KimiEngine.Id, KIMI_RUNTIME_VERSION, kimiRuntimeTarget())
            ?: launch.managed?.takeIf { it.version == KIMI_RUNTIME_VERSION }
    } else {
        throw EngineException(
            io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure.Engine(
                io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason.RequirementsNotMet,
            ),
        )
    }

    suspend fun prepare(manager: EngineManager, launch: LaunchContext = LaunchContext()) = mutex.withLock {
        try {
            val current = try {
                locateRelease(launch)
            } catch (error: EngineException) {
                if (launch.settings.runtime != RuntimePreference.Auto || launch.isInstallCandidate) throw error
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
                // Preparation is not ready until the selected runtime independently matches the release pin.
                locateRelease(launch)
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
