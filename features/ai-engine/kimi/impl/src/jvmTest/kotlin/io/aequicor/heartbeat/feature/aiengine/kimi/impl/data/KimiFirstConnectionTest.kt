package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class KimiFirstConnectionTest {
    @Test
    fun `inspection does not execute an unpinned self consistent runtime`() = runTest {
        val root = Files.createTempDirectory("kimi-untrusted-inspection").toFile()
        val client = HttpClient(MockEngine { error("Inspection must stay offline") })
        try {
            val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val executable = root.resolve(if (isWindows) "kimi.exe" else "kimi")
            val marker = root.resolve("executed")
            executable.writeText("#!/bin/sh\ntouch '${marker.absolutePath}'\n")
            executable.setExecutable(true)
            val hash = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
                .joinToString("") { "%02x".format(it) }
            root.resolve("runtime.properties").writeText(
                "version=$KIMI_RUNTIME_VERSION\nsourceCommit=$KIMI_SOURCE_COMMIT\n" +
                    "permissionGate=$KIMI_PERMISSION_GATE\nsha256=$hash\ntarget=${kimiRuntimeTarget()}\n",
            )
            val setup = KimiRuntimeSetup(NoManagedKimiRuntime, TestKimiDispatchers, resourcesDirectory = null)
            val backend = JvmKimiBackend(
                transports = unused(), clients = unused(), workspaces = unused(), dispatchers = TestKimiDispatchers,
                toggles = unused(), tools = unused(), bridge = unused(), resources = unused(),
                localApi = JvmKimiLocalApi(client, TestKimiDispatchers, setup), setup = setup, profile = unused(),
                compatibilityProbe = KimiCompatibilityProbe(setup, unused(), unused(), TestKimiDispatchers),
            )
            val managed = ManagedInstall(KIMI_RUNTIME_VERSION, executable.absolutePath, Instant.fromEpochSeconds(0))

            val result = backend.inspect(LaunchContext(managed = managed, isInstallCandidate = true))

            assertEquals(InstallSource.Managed, result.source)
            assertFalse(result.isRunnable)
            assertFalse(marker.exists())
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `old installer reaches CLI sign in without a packaged or system runtime`() = runTest {
        val client = HttpClient(MockEngine { error("Inspection must stay offline") })
        try {
            val setup = KimiRuntimeSetup(NoManagedKimiRuntime, TestKimiDispatchers, resourcesDirectory = null)
            val backend = JvmKimiBackend(
                transports = unused(), clients = unused(), workspaces = unused(), dispatchers = TestKimiDispatchers,
                toggles = unused(), tools = unused(), bridge = unused(), resources = unused(),
                localApi = JvmKimiLocalApi(client, TestKimiDispatchers, setup), setup = setup, profile = unused(),
                compatibilityProbe = KimiCompatibilityProbe(setup, unused(), unused(), TestKimiDispatchers),
            )
            val registration = io.aequicor.heartbeat.feature.aiengine.kimi.impl.di.KimiBindings.registration(
                lazyOf(backend),
            )
            kotlin.test.assertTrue(registration.management.isRuntimePreparedOnLogin)
            // These are the wizard's availability and management inspections before it starts EngineAction.Login.
            assertEquals(EngineAvailability.Available, backend.checkRequirements())
            assertEquals(InstallSource.Missing, backend.inspect(LaunchContext()).source)
            assertEquals(LoginState.SignedOut, backend.loginStatus(LaunchContext()))
            val source = AuthSource.CliLogin(
                AuthSourceInfo(KimiEngine.AuthSource, "Kimi", AuthRevision.Known("r1")),
                AuthScope(ProviderId("kimi"), EndpointOrigin("https://api.kimi.com")),
                KimiEngine.AuthOwner,
                KimiEngine.AuthLocation,
            )
            assertTrue(backend.isAdmittedRuntimeRequiredForModels)
            assertEquals(
                EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown),
                assertFailsWith<EngineException> {
                    backend.discoverModels(source, EngineContext(KimiEngine.Id, EngineBindingId("binding")))
                }.failure,
            )
        } finally {
            client.close()
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, _, _ -> error("Missing runtime must not start a process or inspect credentials") } as T
}
