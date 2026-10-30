package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

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
            )
            // These are the wizard's availability and management inspections before it starts EngineAction.Login.
            assertEquals(EngineAvailability.Available, backend.checkRequirements())
            assertEquals(InstallSource.Missing, backend.inspect(LaunchContext()).source)
            assertEquals(LoginState.SignedOut, backend.loginStatus(LaunchContext()))
        } finally {
            client.close()
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, _, _ -> error("Missing runtime must not start a process or inspect credentials") } as T
}
