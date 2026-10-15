package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PiProcessPreparationTest {
    @Test
    fun `first use installs a known baseline instead of an older or equal managed Pi`() = runTest {
        for (version in listOf("0.86.0", "0.87.1")) {
            withLauncher({ managedContext(it, version) }) { launcher, distribution, cache, _ ->
                assertNull(distribution.executable())
                assertEquals(InstallSource.Bundled, launcher.startup().source)
                assertTrue(launcher.canPrepare())
                assertEquals(0, cache.calls)

                val prepared = launcher.prepare()

                assertEquals(InstallSource.Bundled, prepared.source)
                assertEquals(distribution.executable(), prepared.executable)
                assertTrue(Files.isExecutable(prepared.executable))
                assertEquals(EnginePreparation.Ready, launcher.preparation.value)
                assertEquals(1, cache.calls)
                assertEquals(prepared, launcher.prepare())
                assertEquals(1, cache.calls)
            }
        }
    }

    @Test
    fun `custom and strictly newer managed Pi retain priority without downloading the baseline`() = runTest {
        val contexts: List<Pair<(Path) -> LaunchContext, InstallSource>> = listOf(
            { root: Path -> managedContext(root, "0.88.0") } to InstallSource.Managed,
            { root: Path ->
                managedContext(root, "0.86.0").copy(
                    settings = LaunchSettings(executable = root.resolve("custom-pi").toString()),
                )
            } to InstallSource.Custom,
        )
        for ((context, source) in contexts) {
            withLauncher(context) { launcher, distribution, cache, launch ->
                val expected = Path.of(launch.settings.executable ?: checkNotNull(launch.managed).executable)
                Files.writeString(expected, "selected executable")
                assertTrue(expected.toFile().setExecutable(true, false))
                assertFalse(launcher.canPrepare())

                val prepared = launcher.prepare()

                assertEquals(source, prepared.source)
                assertEquals(expected, prepared.executable)
                assertEquals(0, cache.calls)
                assertNull(distribution.executable())
            }
        }
    }

    private fun managedContext(root: Path, version: String): LaunchContext {
        val executable = Files.writeString(root.resolve("managed-pi"), "managed executable")
        check(executable.toFile().setExecutable(true, false))
        return LaunchContext(managed = ManagedInstall(version, executable.toString(), Instant.fromEpochSeconds(1)))
    }

    private suspend fun TestScope.withLauncher(
        context: (Path) -> LaunchContext,
        test: suspend (PiProcessLauncher, PiRuntimeDistribution, PiArtifactFixture, LaunchContext) -> Unit,
    ) = piTemporary { root ->
        val dispatchers = piDispatchers()
        val cache = PiArtifactFixture(piArchive(root))
        val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, dispatchers)
        val launch = context(root)
        val client = HttpClient(MockEngine { error("Preparation must not use provider APIs") })
        val oldResources = System.getProperty(RESOURCES_PROPERTY)
        System.setProperty(RESOURCES_PROPERTY, root.toString())
        try {
            val launcher = PiProcessLauncher(
                dispatchers = dispatchers,
                secrets = unused(),
                searchBridge = unused(),
                nativeWeb = PiNativeWeb(client),
                catalog = PiCompatibleCatalog(client),
                builtin = PiBuiltinCatalog { _, _ -> error("Preparation must not launch a model probe") },
                toggles = unused(),
                storage = PiStorage(StorageRoot { root.toString() }, root.resolve("legacy")),
                profile = unused(),
                stores = unused(),
                launches = EngineLaunchConfig { launch },
                distribution = distribution,
            )
            test(launcher, distribution, cache, launch)
        } finally {
            client.close()
            if (oldResources == null) {
                System.clearProperty(RESOURCES_PROPERTY)
            } else {
                System.setProperty(RESOURCES_PROPERTY, oldResources)
            }
        }
    }

    /** Fail-fast ports ensure preparation never touches credentials, native processes or profile services. */
    private inline fun <reified T : Any> unused(): T = T::class.java.cast(
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Preparation unexpectedly called ${T::class.simpleName}.${method.name}")
        },
    )
}

private const val RESOURCES_PROPERTY = "compose.application.resources.dir"
