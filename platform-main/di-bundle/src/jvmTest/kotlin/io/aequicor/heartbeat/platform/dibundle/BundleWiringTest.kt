package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.applogs.api.AppLogsRoute
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEnabled
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdatesRoute
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
        val expected = listOf(
            SettingsRoute::class,
            WelcomeRoute::class,
            TogglesPanelRoute::class,
            AppLogsRoute::class,
            AppUpdatesRoute::class,
        )
        assertTrue(expected.all { it in routes }, routes.toString())
        assertEquals(2, shell.appDeepLinks.count { it.pattern.startsWith("settings") }, shell.appDeepLinks.toString())
    }

    @Test
    fun `the update check starts with the launch`() {
        val startups = (app as TestShellAccessors).appStartups.map { it::class.simpleName }
        assertTrue("AppUpdateStartup" in startups, startups.toString())
    }
}

/** The desktop graph with the `engines` bundle: the facade and the engine adapters come from its own graph. */
class EnginesBundleWiringTest {
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
    fun `the production graph starts and seals the engines bundle without findings`() {
        val graph = createHeartbeatGraph(isDevelopment = true) as JvmHeartbeatGraph
        val health = graph.bundleHost.health
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("engines")), health.toString())
    }

    @Test
    fun `the profile gets the facade and the adapters of the bundle`() = runTest {
        val graph = app.profileSessions.open(ProfileId("engines-wiring")).graph
        val engines = (graph as RegisteredEnginesAccess).registeredEngines.all.map { it.descriptor.id.value }
        assertTrue(engines.isNotEmpty(), "the adapters of the bundle must reach the profile")
    }

    @Test
    fun `engine management of the bundle installs through the desktop store`() = runTest {
        val graph = app.profileSessions.open(ProfileId("engines-install")).graph
        val management = (graph as AiEngineTestAccessors).engineManagement
        val stores = dependenciesOfType(management, MANAGED_INSTALL_STORE).map { it.javaClass.name }
        // Regression: while the mobile fallback was compiled on Desktop too (ranked below the desktop store), the
        // profile graph of the bundle got the fallback and every install failed with `Install(reason=Unsupported)`.
        assertTrue(DESKTOP_MANAGED_INSTALL_STORE in stores, stores.toString())
        assertTrue(stores.none { it.endsWith(".UnsupportedManagedInstallStore") }, stores.toString())
    }

    @Test
    fun `engine adapters of the bundle bridge hosted tools through the desktop bridge`() = runTest {
        val graph = app.profileSessions.open(ProfileId("engines-bridge")).graph
        val pi = (graph as AiEngineAccessors).piEngine
        val bridges = dependenciesOfType(pi, AGENT_TOOL_BRIDGE, depth = 4).map { it.javaClass.name }
        // Guard, not a regression test: a common fallback (priority 0) still competes with the Desktop bridge
        // (priority 1), both in the profile scope. The adapters of the bundle must get the Desktop one.
        assertTrue(DESKTOP_AGENT_TOOL_BRIDGE in bridges, bridges.toString())
        assertTrue(bridges.none { it.endsWith(".UnsupportedAgentToolBridge") }, bridges.toString())
    }

    private companion object {
        const val FACADE = "io.aequicor.heartbeat.feature.aiengine.facade"
        const val AGENT_TOOL_BRIDGE = "$FACADE.api.AgentToolBridge"
        const val DESKTOP_AGENT_TOOL_BRIDGE = "$FACADE.impl.data.DesktopAgentToolBridge"
        const val MANAGED_INSTALL_STORE = "$FACADE.impl.domain.ManagedInstallStore"
        const val DESKTOP_MANAGED_INSTALL_STORE = "$FACADE.impl.data.install.DesktopManagedInstallStore"
    }
}

/**
 * Values of type [typeName] that [root] holds in its fields, directly or through the fields of its fields up to
 * [depth] levels: the facade implementation is internal to the `engines` bundle, so the test reads it reflectively.
 * `java.*`/`kotlin.*` holders are not walked, so a value behind `kotlin.Lazy` is not found (the assertion fails
 * loudly).
 */
private fun dependenciesOfType(root: Any, typeName: String, depth: Int = 3): List<Any> {
    val type = Class.forName(typeName, false, root.javaClass.classLoader)
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    val found = mutableListOf<Any>()
    fun visit(value: Any, level: Int) {
        if (!seen.add(value)) return
        if (type.isInstance(value)) found += value
        if (level == depth || value.javaClass.name.startsWith("java.") || value.javaClass.name.startsWith("kotlin.")) {
            return
        }
        generateSequence<Class<*>>(value.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.type.isPrimitive }
            .forEach { field ->
                if (field.trySetAccessible()) field.get(value)?.let { visit(it, level + 1) }
            }
    }
    visit(root, 0)
    return found
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
