package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
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
import kotlin.test.assertTrue

/** The desktop graph with the `agent-runtime` bundle: worktrees and agent learning come from its own graphs. */
class AgentRuntimeBundleWiringTest {
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
    fun `the production graph starts and seals the agent-runtime bundle without findings`() {
        val graph = createHeartbeatGraph(isDevelopment = true) as JvmHeartbeatGraph
        val health = graph.bundleHost.health
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("agent-runtime")), health.toString())
    }

    @Test
    fun `toggles of the bundle reach the application toggles`() {
        val registered = (app as TestToggleAccessors).toggleControl.registered.map { it.key }
        listOf(WorktreeModeEnabled, AgentLearningEnabled).forEach { toggle ->
            assertTrue(toggle.key in registered, "${toggle.key} is missing from $registered")
        }
    }

    @Test
    fun `profile startups of the bundle run with the profile`() = runTest {
        val session = app.profileSessions.open(ProfileId("agent-runtime-startups"))
        val names = (session.graph as ProfileStartupAccess).profileStartups.map { it::class.simpleName }
        assertTrue("WorktreeStartup" in names, names.toString())
    }

    @Test
    fun `agent tools of the bundle reach the tool set of the engines bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("agent-runtime-tools"))
        val groups = (session.graph as TestAgentRuntimeProfileAccessors).runtimeAgentTools.catalog().map { it.id }
        assertTrue("worktree" in groups, groups.toString())
        assertTrue("learning" in groups, groups.toString())
    }

    @Test
    fun `configuration sections of the bundle reach the foundation set`() = runTest {
        val session = app.profileSessions.open(ProfileId("agent-runtime-config"))
        val sections = (session.graph as TestAgentRuntimeProfileAccessors).runtimeConfigSections
        assertTrue(sections.any { it::class.simpleName == "LearningConfigSection" }, sections.toString())
    }
}

/** Test access to what the foundation profile graph takes from the `agent-runtime` bundle. */
@dev.zacsweers.metro.ContributesTo(ProfileScope::class)
interface TestAgentRuntimeProfileAccessors {
    val runtimeAgentTools: ProfileAgentTools
    val runtimeConfigSections: Set<ConfigSection>
}
