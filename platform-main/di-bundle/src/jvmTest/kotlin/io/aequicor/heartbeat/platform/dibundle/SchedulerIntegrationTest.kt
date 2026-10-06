package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SchedulerIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val session = SCHEDULER_TEST_SESSION

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `the scheduler is registered, disabled by default and offers no tools then`() = runTest {
        assertTrue(SchedulerEnabled in toggles.toggleControl.registered)
        assertTrue(SchedulerActions in toggles.toggleControl.registered)
        assertFalse(SchedulerEnabled.default || SchedulerActions.default)
        val profile = app.profileSessions.open(ProfileId("scheduler-off"))
        val names = (profile.graph as TestSchedulerAccessors).schedulerTools.specifications(null).map { it.name }
        assertTrue(names.none { it.startsWith("scheduler_") }, names.toString())
        app.profileSessions.close()
    }

    @Test
    fun `a wake scheduled by an agent survives reopening the profile`() = runTest {
        toggles.toggleControl.setOverride(SchedulerEnabled, true)
        val first = app.profileSessions.open(ProfileId("scheduler"))
        val accessors = first.graph as TestSchedulerAccessors
        assertTrue(SchedulerTools.SLEEP in accessors.schedulerTools.specifications(null).map { it.name })
        ready(accessors)
        val arguments = JsonObject(
            mapOf(
                SchedulerTools.Arguments.AFTER_SECONDS to JsonPrimitive(3_600),
                SchedulerTools.Arguments.NOTE to JsonPrimitive("check the nightly build"),
            ),
        )
        val slept = withContext(app.dispatchers.default) {
            accessors.schedulerTools.execute(context(), SchedulerTools.SLEEP, arguments)
        }
        assertFalse(slept.isError, slept.text)
        app.profileSessions.close()

        val reopened = app.profileSessions.open(ProfileId("scheduler"))
        val wakes = ready(reopened.graph as TestSchedulerAccessors).wakes
        assertEquals(listOf(session), wakes.map { it.session })
        assertEquals("check the nightly build", wakes.single().request.note)
        app.profileSessions.close()
    }

    private suspend fun ready(accessors: TestSchedulerAccessors): SchedulerState.Ready =
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // flatMapLatest over the registry
                accessors.schedulerMachines.observe(SchedulerMachineKey)
                    .filterNotNull()
                    .flatMapLatest { it.state }
                    .filterIsInstance<SchedulerState.Ready>()
                    .first()
            }
        }

    private fun context() = AgentToolContext(session, null, TurnId("turn"), trust = TrustLevel.Full)
}

@ContributesTo(ProfileScope::class)
interface TestSchedulerAccessors {
    val schedulerTools: ProfileAgentTools
    val schedulerMachines: MachineRegistry
}

/** Only this fixture's session has a host; unrelated integration sessions retain their real ownership. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class TestScheduledSessionHost : ScheduledSessionHost {
    override val priority: Int = 1

    override suspend fun owns(session: SessionRef): Boolean = session == SCHEDULER_TEST_SESSION

    override suspend fun wake(request: WakeRequest, prompt: WakePrompt): Unit = error("The test deadline is not due")
}

private val SCHEDULER_TEST_SESSION = SessionRef(EngineId("koog"), SessionSourceId("local"), "scheduler-owned")
