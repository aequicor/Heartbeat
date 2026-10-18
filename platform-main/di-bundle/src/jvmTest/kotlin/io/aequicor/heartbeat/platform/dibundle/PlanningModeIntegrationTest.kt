package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanEdit
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineKey
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PlanningModeIntegrationTest {
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
    fun `planning is a registered toggle that starts off and leaves storage untouched`() = runTest {
        assertTrue(PlanningModeEnabled in toggles.toggleControl.registered)
        assertFalse(PlanningModeEnabled.default)
        val profile = app.profileSessions.open(ProfileId("planning-off"))
        assertIs<PlanningState.Idle>((profile.graph as TestPlanningAccessors).planning.state.value)
        app.profileSessions.close()
        assertTrue(planFiles().isEmpty())
    }

    @Test
    fun `a plan written in planning mode is restored from the profile directory after reopening`() = runTest {
        toggles.toggleControl.setOverride(PlanningModeEnabled, true)
        val first = app.profileSessions.open(ProfileId("planning"))
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                val machine = (first.graph as TestPlanningAccessors).machines.observe(PlanningMachineKey)
                    .filterNotNull().first()
                machine.state.filterIsInstance<PlanningState.Ready>().first()
                machine.send(PlanningIntent.Public.Enter(Chat, At))
                machine.send(
                    PlanningIntent.Public.Write(Chat, PlanEdit.Replace("# Plan\n\nSteps"), 0, PlanAuthor.User, At),
                )
                while (planFiles().none { it.readText() == "# Plan\n\nSteps" }) delay(10.milliseconds)
            }
        }
        app.profileSessions.close()

        val reopened = app.profileSessions.open(ProfileId("planning"))
        val plan = withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                (reopened.graph as TestPlanningAccessors).planning.state.filterIsInstance<PlanningState.Ready>()
                    .first { Chat in it.plans }.plans.getValue(Chat)
            }
        }
        assertEquals("# Plan\n\nSteps", plan.text)
        assertEquals(1, plan.revision)
        assertEquals(PlanMode.Planning, plan.mode)
        app.profileSessions.close()
    }

    private fun planFiles(): List<File> =
        File(persisted.storageRoot).walk().filter { it.name == "plan.md" && it.parentFile.name == Chat.value }.toList()

    private companion object {
        val Chat = PlanChat("chat-1")
        val At: Instant = Instant.fromEpochSeconds(1_000)
    }
}

@ContributesTo(ProfileScope::class)
interface TestPlanningAccessors {
    val planning: PlanningView
    val machines: MachineRegistry
}
