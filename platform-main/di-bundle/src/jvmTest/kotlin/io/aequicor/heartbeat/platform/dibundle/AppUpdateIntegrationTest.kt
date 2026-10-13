package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEnabled
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateMachineKey
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `app updates are a registered toggle enabled by default`() = runTest {
        assertTrue(AppUpdateEnabled in toggles.toggleControl.registered)
        assertEquals(true, toggles.featureToggles.get(AppUpdateEnabled))
    }

    // Its own scheduler: runTest must not advance the startup's periodic check on the main test dispatcher forever.
    @Test
    fun `the desktop startup runs the update machine, which a launch outside an installed release declines`() =
        runTest(StandardTestDispatcher(TestCoroutineScheduler())) {
            assertNull(app.machines.find(AppUpdateMachineKey))

            (app as AppStartupAccessors).appStartups.forEach { it.start() }

            val updates = assertNotNull(app.machines.find(AppUpdateMachineKey))
            val state = withContext(Dispatchers.Default) {
                withTimeout(TIMEOUT_MILLIS) { updates.state.first { it is AppUpdateState.Unsupported } }
            }
            assertEquals(AppUpdateState.Unsupported(UnsupportedReason.NotReleaseBuild), state)
        }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
