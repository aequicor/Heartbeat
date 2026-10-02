package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerUseIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `every computer use toggle is registered and disabled by default`() = runTest {
        val declared = listOf(
            ComputerUseEnabled,
            ComputerUseNativeRouting,
        )
        declared.forEach { toggle ->
            assertTrue(toggle in toggles.toggleControl.registered, "${toggle.key} is not registered")
            assertFalse(toggle.default, "${toggle.key} must start disabled")
            assertFalse(toggles.featureToggles.get(toggle))
            assertEquals("computer_use", toggle.owner)
        }
    }

    @Test
    fun `the machine stays closed while the feature is disabled`() = runTest {
        app.profileSessions.open(ProfileId("computer-use-off"))
        assertNull(app.machines.find(ComputerUseMachineKey))
        assertEquals(
            SendResult.NotRunning,
            app.machines.send(ComputerUseMachineKey, ComputerUseIntent.Public.Revoke),
        )
        app.profileSessions.close()
    }

    @Test
    fun `the profile starts the machine once the feature is enabled`() = runTest {
        toggles.toggleControl.setOverride(ComputerUseEnabled, true)
        val profile = app.profileSessions.open(ProfileId("computer-use-on"))
        assertNull(app.machines.find(ComputerUseMachineKey))
        val settings = (profile.graph as TestStorageAccessors).stores
            .keyValue(KeyValueSpec("computer_use", areValuesLogged = true))
        settings.set(booleanKey("enabled"), true)
        val machine = bounded("computer use machine") {
            app.machines.observe(ComputerUseMachineKey).first { it != null }
        }
        assertNotNull(machine)
        val state = bounded("computer use probe") {
            machine.state.first { it !is ComputerUseState.Idle && it !is ComputerUseState.Checking }
        }
        assertTrue(
            state is ComputerUseState.Ready ||
                state is ComputerUseState.Unavailable ||
                state is ComputerUseState.Failed,
            "unexpected state after the probe: $state",
        )
        settings.set(booleanKey("enabled"), false)
        bounded("profile switch revokes computer use") { machine.state.first { it is ComputerUseState.Idle } }
        app.profileSessions.close()
        assertNull(app.machines.find(ComputerUseMachineKey))
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
