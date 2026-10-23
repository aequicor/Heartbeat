package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionEngineTransfer
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.AutocompleteEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRemoteScreens
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermissionGuide
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfiguration
import io.aequicor.heartbeat.feature.feedback.api.FeedbackEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRemoteScreens
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
import kotlin.test.assertSame
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
        listOf(
            FeedbackEnabled,
            EffortConfiguration,
            PlanningModeEnabled,
            AutocompleteEnabled,
            SessionEngineTransfer,
            ContextOptimizationEnabled,
            ComputerUseEnabled,
            QuestionnaireEnabled,
            ChecklistEnabled,
            AttachmentsEnabled,
        ).forEach { toggle ->
            assertTrue(toggle.key in registered, "${toggle.key} is missing from $registered")
        }
    }

    @Test
    fun `profile startups of the bundle run with the profile`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-startups"))
        val names = (session.graph as ProfileStartupAccess).profileStartups.map { it::class.simpleName }
        assertTrue("FeedbackStartup" in names, names.toString())
        assertTrue("PlanningStartup" in names, names.toString())
        assertTrue("QuestionnaireStartup" in names, names.toString())
        assertTrue("ChecklistStartup" in names, names.toString())
        assertTrue("AttachmentStartup" in names, names.toString())
    }

    @Test
    fun `the checklist screens of the bundle reach the foundation`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-checklist"))
        assertNotNull((session.graph as TestStudioProfileAccessors).studioChecklistScreens)
    }

    @Test
    fun `the questionnaire screens of the bundle reach the foundation`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-questionnaire"))
        assertNotNull((session.graph as TestStudioProfileAccessors).studioQuestionnaireScreens)
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
    fun `the composer suggestions of the bundle reach the foundation`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-composer"))
        assertNotNull((session.graph as TestStudioProfileAccessors).studioComposerAssists)
    }

    @Test
    fun `agent tools of the bundle reach the tool set of the engines bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-tools"))
        val groups = (session.graph as TestStudioProfileAccessors).studioAgentTools.catalog().map { it.id }
        assertTrue("planning" in groups, groups.toString())
    }

    @Test
    fun `computer use presentation and permission guide of the bundle reach the foundation`() {
        val accessors = app as TestStudioAppAccessors
        assertNotNull(accessors.studioCapturePresentation)
        assertSame(accessors.studioPermissionGuide, accessors.studioPermissionGuide)
    }

    @Test
    fun `computer use tools of the bundle reach the tool set of the engines bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-computer-use"))
        val groups = (session.graph as TestStudioProfileAccessors).studioAgentTools.catalog().map { it.id }
        assertTrue("computer" in groups, groups.toString())
    }

    @Test
    fun `the context optimization of the bundle reaches the foundation and its hooks reach the facade`() = runTest {
        val session = app.profileSessions.open(ProfileId("studio-context"))
        val accessors = session.graph as TestStudioProfileAccessors
        assertNotNull(accessors.studioContextOptimization)
        val host = (app as BundleHostAccess).bundleHost
        val hooks = host.foundationProfileImports(TestBundleSessionHooks::class, accessors.studioProfileScope)
            .sessionHooks.map { it::class.simpleName }
        assertTrue("ContextLedgerHook" in hooks, hooks.toString())
        assertTrue("ContextHandoffHook" in hooks, hooks.toString())
    }
}

/** Test access to what the foundation application graph takes from the `studio` bundle. */
@dev.zacsweers.metro.ContributesTo(AppScope::class)
interface TestStudioAppAccessors {
    val studioCapturePresentation: ComputerUseCapturePresentation
    val studioPermissionGuide: ComputerUsePermissionGuide
}

/** Test access to what the foundation profile graph takes from the `studio` bundle. */
@dev.zacsweers.metro.ContributesTo(ProfileScope::class)
interface TestStudioProfileAccessors {
    val studioEffortChoices: EffortChoicesView
    val studioPlanningView: PlanningView
    val studioComposerAssists: ComposerAssists
    val studioAgentTools: ProfileAgentTools
    val studioContextOptimization: ContextOptimizationView
    val studioQuestionnaireScreens: QuestionnaireRemoteScreens
    val studioChecklistScreens: ChecklistRemoteScreens

    @ForScope(ProfileScope::class)
    val studioProfileScope: ScopeHandle
}
