package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `configuration and trust failures release the key and temporary agent directory`() = runTest {
        for (stage in listOf("vault", "trust", "cancel")) {
            val key = Secret("provider-key".toCharArray())
            var isPreparationReached = false
            val failure = if (stage == "cancel") {
                CancellationException("cancel preparation")
            } else {
                IllegalStateException("failed")
            }
            val (secrets, workspaces) = failingPorts(key, failure) { isPreparationReached = true }
            val stores = port<DataStores> { method ->
                check(method == "getOwner")
                StorageOwner.Profile(ProfileId("profile"))
            }
            val mcp = Json.parseToJsonElement(
                """{"mcpServers":{"test":{"env":{"TOKEN":"${secretReference("TOKEN")}"}}}}""",
            ).jsonObject
            withLauncher(
                context = { root ->
                    managedContext(root, "0.88.0").copy(
                        isConfigured = true,
                        config = EngineConfigValues(mapOf(PiDocuments.Mcp to ConfigDocument.Json(mcp))),
                        trust = mapOf(WorkspaceRef("project") to ProjectTrust.Trusted),
                    )
                },
                secrets = secrets,
                stores = stores,
                workspaces = workspaces,
            ) { launcher, _, _, launch ->
                val root = Path.of(checkNotNull(launch.managed).executable).parent
                val start: suspend () -> Unit = {
                    launcher.start(
                        RuntimeSource,
                        root.toString().takeIf { stage == "trust" },
                        event = {},
                        failed = {},
                        plan = PiLaunchPlan(ResolvedToolPolicy(), null, emptySet()),
                    )
                }
                if (stage == "cancel") {
                    assertFailsWith<CancellationException> { start() }
                } else {
                    assertFailsWith<EngineException> { start() }
                }
                assertTrue(isPreparationReached)
                assertFailsWith<IllegalStateException> { key.reveal { it.size } }
                val runtime = PiStorage(StorageRoot { root.toString() }, root.resolve("legacy"))
                    .profileRoot("profile").resolve("runtime")
                Files.list(runtime).use { assertEquals(0L, it.count()) }
            }
        }
    }

    private fun failingPorts(
        key: Secret,
        failure: Throwable,
        reached: () -> Unit,
    ): Pair<SecretStore, LocalWorkspaces> {
        val secrets = port<SecretStore> { method ->
            when (method) {
                "read" -> key

                "readFor" -> {
                    reached()
                    throw failure
                }

                else -> error("Unexpected secret call $method")
            }
        }
        val workspaces = port<LocalWorkspaces> { method ->
            when (method) {
                "isAvailable" -> true

                "resolve" -> {
                    reached()
                    throw failure
                }

                else -> error("Unexpected workspace call $method")
            }
        }
        return secrets to workspaces
    }

    private inline fun <reified T : Any> port(crossinline call: (String) -> Any?): T = T::class.java.cast(
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) },
    )

    private fun managedContext(root: Path, version: String): LaunchContext {
        val executable = Files.writeString(root.resolve("managed-pi"), "managed executable")
        check(executable.toFile().setExecutable(true, false))
        return LaunchContext(managed = ManagedInstall(version, executable.toString(), Instant.fromEpochSeconds(1)))
    }

    private suspend fun TestScope.withLauncher(
        context: (Path) -> LaunchContext,
        secrets: SecretStore = unused(),
        stores: DataStores = unused(),
        workspaces: LocalWorkspaces = unused(),
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
                secrets = secrets,
                searchBridge = unused(),
                nativeWeb = PiNativeWeb(client),
                catalog = PiCompatibleCatalog(client),
                builtin = PiBuiltinCatalog { _, _ -> error("Preparation must not launch a model probe") },
                toggles = unused(),
                storage = PiStorage(StorageRoot { root.toString() }, root.resolve("legacy")),
                profile = unused(),
                stores = stores,
                launches = EngineLaunchConfig { launch },
                distribution = distribution,
                workspaces = workspaces,
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
