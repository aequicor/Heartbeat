package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.platform.dibundle.AiEngineAccessors
import io.aequicor.heartbeat.platform.dibundle.AppShutdownAccessors
import io.aequicor.heartbeat.platform.dibundle.bundleHealth
import io.aequicor.heartbeat.platform.dibundle.closeScopes
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.profileBundleHealth
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The application as shipped: every desktop bundle is on the class path (`runtimeOnly`), the static graph holds
 * only the foundation. Each bundle starts healthy, opens its profile graph, and the foundation reaches what they
 * export.
 */
class DesktopBundlesTest {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `every desktop bundle starts and opens its profile graph`() = runBlocking {
        // On Windows the storage root follows %APPDATA%, not user.home: keep the test off a real profile.
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        val home = Files.createTempDirectory("hb-bundles-home")
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            val graph = createHeartbeatGraph(isDevelopment = true)
            val expected = setOf("agent-runtime", "engines", "shell", "studio", "web").map(::BundleId).toSet()
            val health = graph.bundleHealth()
            assertEquals(expected, health.keys.filter { it in expected }.toSet(), health.toString())
            assertTrue(expected.all { health[it] == BundleHealth.Healthy }, health.toString())

            val session = graph.profileSessions.open(ProfileId("bundles-smoke"))
            val profile = graph.profileBundleHealth(session.graph)
            // The shell has no profile graph: its features are app-level.
            val withProfile = expected - BundleId("shell")
            assertEquals(withProfile, profile.keys, profile.toString())
            assertTrue(profile.values.all { it == BundleHealth.Healthy }, profile.toString())

            // The foundation reaches the services of the bundles it does not compile.
            val engines = session.graph as AiEngineAccessors
            assertNotNull(engines.engineDefaults)
            assertTrue(engines.engineRegistrations.isNotEmpty(), "the engines bundle registers its adapters")
            graph.profileSessions.close()
            (graph as AppShutdownAccessors).closeScopes()
        } finally {
            System.setProperty("user.home", previousHome.orEmpty())
            home.deleteRecursively()
        }
    }
}
