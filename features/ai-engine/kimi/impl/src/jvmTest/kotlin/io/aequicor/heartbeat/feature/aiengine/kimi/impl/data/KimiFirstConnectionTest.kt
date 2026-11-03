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
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KimiFirstConnectionTest {
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
