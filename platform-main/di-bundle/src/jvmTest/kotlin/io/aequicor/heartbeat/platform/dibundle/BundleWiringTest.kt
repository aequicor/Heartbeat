package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.feature.applogs.api.AppLogsRoute
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEnabled
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import io.aequicor.heartbeat.feature.welcome.api.CinematicIntro
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The desktop graph with the `web` bundle on the class path: discovery, sealing and the bundle's services. */
class BundleWiringTest {
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
    fun `the production graph starts and seals the web bundle without findings`() {
        val graph = createHeartbeatGraph(isDevelopment = true) as JvmHeartbeatGraph
        val health = graph.bundleHost.health
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("web")), health.toString())
    }

    @Test
    fun `any application graph starts the bundles on its first use`() {
        val renderer = (app as PlantUmlAccessors).plantUml
        assertTrue(renderer !is UnavailablePlantUmlRenderer, "the bundle renderer must be wired in, not the fallback")
        assertEquals(BundleHealth.Healthy, (app as BundleHostAccess).bundleHost.health.getValue(BundleId("web")))
    }

    @Test
    fun `the renderer is one instance for every consumer`() {
        val first = (app as PlantUmlAccessors).plantUml
        val second: PlantUmlRenderer = (app as PlantUmlAccessors).plantUml
        assertSame(first, second)
    }

    @Test
    fun `toggle of the bundle reaches the application toggles`() {
        val registered = (app as TestToggleAccessors).toggleControl.registered.map { it.key }
        assertTrue("plantuml.enabled" in registered, registered.toString())
    }
}

/** The desktop graph with the `shell` bundle: its features reach the foundation through the runtime registry. */
class ShellBundleWiringTest {
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
    fun `the production graph starts and seals the shell bundle without findings`() {
        val graph = createHeartbeatGraph(isDevelopment = true) as JvmHeartbeatGraph
        val health = graph.bundleHost.health
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("shell")), health.toString())
    }

    @Test
    fun `toggles of the shell features reach the application toggles`() {
        val registered = (app as TestToggleAccessors).toggleControl.registered.map { it.key }
        listOf(UnifiedSettings, CinematicIntro, AppUpdateEnabled).forEach { toggle ->
            assertTrue(toggle.key in registered, "${toggle.key} is missing from $registered")
        }
    }

    @Test
    fun `routes and deep links of the shell features reach the guest registry`() {
        val shell = app as TestShellAccessors
        val routes = shell.appRoutes.map { it.routeClass }
        val expected = listOf(SettingsRoute::class, WelcomeRoute::class, TogglesPanelRoute::class, AppLogsRoute::class)
        assertTrue(expected.all { it in routes }, routes.toString())
        assertEquals(2, shell.appDeepLinks.count { it.pattern.startsWith("settings") }, shell.appDeepLinks.toString())
    }

    @Test
    fun `the update check starts with the launch`() {
        val startups = (app as TestShellAccessors).appStartups.map { it::class.simpleName }
        assertTrue("AppUpdateStartup" in startups, startups.toString())
    }
}

/** Test accessors of the sets that the `shell` bundle contributes to. */
@dev.zacsweers.metro.ContributesTo(dev.zacsweers.metro.AppScope::class)
interface TestShellAccessors {
    @io.aequicor.heartbeat.core.di.ForScope(dev.zacsweers.metro.AppScope::class)
    val appRoutes: Set<io.aequicor.heartbeat.core.navigation.RouteEntry<*>>

    @io.aequicor.heartbeat.core.di.ForScope(dev.zacsweers.metro.AppScope::class)
    val appDeepLinks: Set<io.aequicor.heartbeat.core.navigation.DeepLinkEntry>
    val appStartups: Set<io.aequicor.heartbeat.core.di.AppStartup>
}

/** Test accessor of the bundle host from the test graph. */
@dev.zacsweers.metro.ContributesTo(dev.zacsweers.metro.AppScope::class)
interface BundleHostAccess {
    val bundleHost: BundleHost
}
