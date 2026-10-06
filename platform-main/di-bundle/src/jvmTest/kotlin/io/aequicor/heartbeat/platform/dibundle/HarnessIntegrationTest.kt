package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessDraft
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ItemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HarnessIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        (app.appScope as OwnedScope).close()
        app.appScope.coroutineScope.coroutineContext[Job]?.join()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `registered default off remains lazy then loads and reuses the library through toggle cycles`() = runTest {
        assertTrue(HarnessEnabled in toggles.toggleControl.registered)
        assertFalse(HarnessEnabled.default)
        val profileId = ProfileId("harness")
        app.profileSessions.open(profileId)
        assertNull(app.machines.find(HarnessMachineKey))
        toggles.toggleControl.setOverride(HarnessEnabled, true)
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                val machine = app.machines.observe(HarnessMachineKey).filterNotNull().first()
                machine.state.filterIsInstance<HarnessState.Ready>().first()
                val code = HarnessItem.Script(ItemId("code"), ItemName("code"), "", "hooks {}")
                machine.send(
                    HarnessIntent.Public.Create(
                        RequestId("create"),
                        HarnessId("harness"),
                        HarnessDraft(HarnessName("test"), "Test", items = listOf(code)),
                        null,
                        Instant.fromEpochMilliseconds(1_000),
                    ),
                )
                val saved = machine.state.filterIsInstance<HarnessState.Ready>().first { it.harnesses.size == 1 }
                assertFalse(saved.isRuntimeAvailable)
                assertEquals(ItemStatus.Unsupported, saved.harnesses.single().itemStatus[code.id])
                toggles.toggleControl.setOverride(HarnessEnabled, false)
                machine.state.first { it.isSuspended }
                toggles.toggleControl.setOverride(HarnessEnabled, true)
                machine.state.first { !it.isSuspended }
                assertSame(machine, app.machines.find(HarnessMachineKey))
            }
        }
        app.profileSessions.close()
        app.profileSessions.open(profileId)
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                val machine = app.machines.observe(HarnessMachineKey).filterNotNull().first()
                val restored = machine.state.filterIsInstance<HarnessState.Ready>().first()
                assertEquals(HarnessName("test"), restored.harnesses.single().harness.name)
                assertFalse(restored.isRuntimeAvailable)
            }
        }
        app.profileSessions.close()
    }
}
