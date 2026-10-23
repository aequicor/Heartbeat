package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfiguration
import io.aequicor.heartbeat.feature.feedback.api.FeedbackEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The desktop graph with the `studio` bundle: the features it assembles come from its own graphs. */
class StudioBundleWiringTest {
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
    fun `the production graph starts and seals the studio bundle without findings`() {
        val graph = createHeartbeatGraph(isDevelopment = true) as JvmHeartbeatGraph
        val health = graph.bundleHost.health
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("studio")), health.toString())
    }

    @Test
    fun `toggles of the bundle reach the application toggles`() {
        val registered = (app as TestToggleAccessors).toggleControl.registered.map { it.key }
        listOf(FeedbackEnabled, EffortConfiguration, PlanningModeEnabled).forEach { toggle ->
            assertTrue(toggle.key in registered, "${toggle.key} is missing from $registered")
        }
    }

    @Test
    fun `profile startups of the bundle run with the profile`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-startups"))
        val names = (session.graph as ProfileStartupAccess).profileStartups.map { it::class.simpleName }
        assertTrue("FeedbackStartup" in names, names.toString())
        assertTrue("PlanningStartup" in names, names.toString())
    }

    @Test
    fun `the effort choices of the bundle reach the foundation`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-effort"))
        assertNotNull((session.graph as TestStudioProfileAccessors).studioEffortChoices)
    }

    @Test
    fun `the plans of the bundle reach the foundation`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-planning"))
        assertNotNull((session.graph as TestStudioProfileAccessors).studioPlanningView)
    }

    @Test
    fun `agent tools of the bundle reach the tool set of the engines bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-tools"))
        val groups = (session.graph as TestStudioProfileAccessors).studioAgentTools.catalog().map { it.id }
        assertTrue("planning" in groups, groups.toString())
    }
}

/** Test access to what the foundation profile graph takes from the `studio` bundle. */
@dev.zacsweers.metro.ContributesTo(ProfileScope::class)
interface TestStudioProfileAccessors {
    val studioEffortChoices: EffortChoicesView
    val studioPlanningView: PlanningView
    val studioAgentTools: ProfileAgentTools
}
