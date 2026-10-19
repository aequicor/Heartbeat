package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.awaitStartupReady
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkerStartupIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `generated graph waits for the exact started services instead of new unscoped contributions`() = runTest {
        val session = app.profileSessions.open(ProfileId("worker-recovery"))
        assertEquals(0, session.startupFailures)
        assertTrue(session.startups.isNotEmpty())
        withContext(app.dispatchers.default) {
            withTimeout(30.seconds) { session.awaitStartupReady() }
        }
    }

    @Test
    fun `disabled harness still completes its initial cleanup snapshot before host readiness`() = runTest {
        (app as TestToggleAccessors).toggleControl.setOverride(HarnessEnabled, false)
        val session = app.profileSessions.open(ProfileId("worker-disabled-harness"))
        withContext(app.dispatchers.default) {
            withTimeout(30.seconds) { session.awaitStartupReady() }
        }
    }
}
