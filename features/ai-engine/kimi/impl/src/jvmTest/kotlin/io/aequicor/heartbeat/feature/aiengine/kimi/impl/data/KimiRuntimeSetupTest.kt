package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeInstaller
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class KimiRuntimeSetupTest {
    @Test
    fun `old installer without seed prepares a managed runtime on first operation`() = runTest {
        val root = Files.createTempDirectory("kimi-first-use").toFile()
        try {
            val executable = root.resolve(
                if (System.getProperty("os.name").startsWith("Windows")) "kimi.exe" else "kimi",
            )
            executable.writeText("host controlled fixture")
            executable.setExecutable(true)
            val hash = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
                .joinToString("") { "%02x".format(it) }
            root.resolve("runtime.properties").writeText(
                "version=$KIMI_RUNTIME_VERSION\nsourceCommit=$KIMI_SOURCE_COMMIT\n" +
                    "permissionGate=$KIMI_PERMISSION_GATE\nsha256=$hash\ntarget=${kimiRuntimeTarget()}\n",
            )
            val installed = ManagedInstall(KIMI_RUNTIME_VERSION, executable.absolutePath, Instant.fromEpochSeconds(0))
            var current: ManagedInstall? = null
            var downloads = 0
            val installer = object : EngineRuntimeInstaller {
                override suspend fun find(engine: EngineId, version: String, target: String?): ManagedInstall? = current
                override suspend fun prepare(
                    engine: EngineId,
                    version: String,
                    manager: EngineManager,
                    target: String?,
                    progress: suspend (EnginePreparation) -> Unit,
                ): ManagedInstall {
                    downloads++
                    progress(EnginePreparation.Downloading(42, 42))
                    current = installed
                    return installed
                }
            }
            val setup = KimiRuntimeSetup(installer, TestKimiDispatchers, resourcesDirectory = null)
            val manager = object : EngineManager {
                override suspend fun inspect(launch: LaunchContext): Installation = setup.locate(launch)
            }
            assertFailsWith<EngineException> { setup.locate() }
            assertEquals(0, downloads, "inspection must not download")
            setup.prepare(manager)
            assertEquals(1, downloads)
            assertEquals(InstallSource.Managed, setup.locate().source)
            assertEquals(executable.absolutePath, setup.locate().path)
            assertEquals(EnginePreparation.Ready, setup.preparation.value)
            val stale = installed.copy(executable = root.resolve("removed/kimi").absolutePath)
            assertEquals(executable.absolutePath, setup.locate(LaunchContext(managed = stale)).path)
            assertFailsWith<EngineException> {
                setup.locate(LaunchContext(managed = stale, isInstallCandidate = true))
            }
            val restarted = KimiRuntimeSetup(installer, TestKimiDispatchers, resourcesDirectory = null)
            restarted.prepare(manager)
            assertEquals(1, downloads, "restart and adapter rollback must work offline")
            executable.appendText("tampered")
            assertFailsWith<EngineException> { restarted.locate() }
        } finally {
            root.deleteRecursively()
        }
    }
}

internal object TestKimiDispatchers : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
}

/** Live probes may use a packaged seed but never download as a side effect of telemetry. */
internal object NoManagedKimiRuntime : EngineRuntimeInstaller {
    override suspend fun find(engine: EngineId, version: String, target: String?): ManagedInstall? = null
    override suspend fun prepare(
        engine: EngineId,
        version: String,
        manager: EngineManager,
        target: String?,
        progress: suspend (EnginePreparation) -> Unit,
    ): ManagedInstall = error("Live telemetry never downloads")
}
