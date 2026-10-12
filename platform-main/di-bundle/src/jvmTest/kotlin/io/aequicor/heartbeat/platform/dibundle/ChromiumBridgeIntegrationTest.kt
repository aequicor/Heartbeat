package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeMachineKey
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromiumBridgeIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val isSupported = System.getProperty("os.name").orEmpty().let {
        it.startsWith("Mac", ignoreCase = true) || it.startsWith("Windows", ignoreCase = true)
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `bridge toggles are registered and disabled by default`() = runTest {
        listOf(ChromiumBridgeEnabled, ChromiumBridgeAgentControl).forEach { toggle ->
            assertTrue(toggle in toggles.toggleControl.registered, "${toggle.key} is not registered")
            assertFalse(toggle.default, "${toggle.key} must start disabled")
            assertEquals("chromium_bridge", toggle.owner)
        }
    }

    @Test
    fun `nothing of the bridge starts while it is disabled`() = runTest {
        app.profileSessions.open(ProfileId("chromium-bridge-off"))

        assertNull(app.machines.find(ChromiumBridgeMachineKey))
        assertEquals(
            SendResult.NotRunning,
            app.machines.send(ChromiumBridgeMachineKey, ChromiumBridgeIntent.Public.Rescan),
        )
        app.profileSessions.close()
    }

    @Test
    fun `enabling serves the socket and switching off closes it`() = runTest {
        toggles.toggleControl.setOverride(ChromiumBridgeEnabled, true)
        app.profileSessions.open(ProfileId("chromium-bridge-on"))
        val machine = bounded("bridge machine") { app.machines.observe(ChromiumBridgeMachineKey).first { it != null } }

        if (isSupported) {
            val enabled = bounded("socket listening") {
                machine.state.first {
                    it is ChromiumBridgeState.Enabled && it.server == BridgeServerStatus.Listening &&
                        it.hosts.isNotEmpty()
                }
            } as ChromiumBridgeState.Enabled
            assertEquals(ChromiumBrowser.entries, enabled.hosts.map { it.browser })
            assertTrue(enabled.connections.isEmpty())
        } else {
            bounded("unsupported platform") { machine.state.first { it == ChromiumBridgeState.Disabled(false) } }
        }
        toggles.toggleControl.setOverride(ChromiumBridgeEnabled, false)
        bounded("bridge disabled") { machine.state.first { it is ChromiumBridgeState.Disabled } }
        app.profileSessions.close()
        assertNull(app.machines.find(ChromiumBridgeMachineKey))
    }

    private suspend fun <T : Any> bounded(checkpoint: String, action: suspend () -> T?): T =
        withContext(app.dispatchers.default) {
            try {
                withTimeout(TIMEOUT_MILLIS) { action() } ?: throw AssertionError("Timed out at $checkpoint")
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("Timed out at $checkpoint", e)
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
