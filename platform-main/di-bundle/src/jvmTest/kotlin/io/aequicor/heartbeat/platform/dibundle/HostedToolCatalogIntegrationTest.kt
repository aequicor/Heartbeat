package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Settings can turn off only catalogued tools, so every declaration a contribution offers must be in its catalog. */
class HostedToolCatalogIntegrationTest {
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
    fun `declarations of every contribution are catalogued in project and detached scopes`() = runTest {
        // Every flag on: declarations behind feature toggles are checked too.
        toggles.toggleControl.registered.filterIsInstance<FeatureToggle.Flag>().forEach {
            toggles.toggleControl.setOverride(it, true)
        }
        val profile = app.profileSessions.open(ProfileId("tool-catalog")).graph as HostedToolCatalogAccessors
        val directory = File(persisted.storageRoot, "catalog-workspace").apply { mkdirs() }
        val workspace = profile.localWorkspaces.registerManaged(directory.absolutePath).ref
        val scopes = listOf(AgentToolScope(workspace), AgentToolScope(null))
        val contributions = profile.toolContributions
        assertTrue(contributions.isNotEmpty())
        var declared = 0
        for (contribution in contributions) {
            val catalog = contribution.catalog.map { it.name }.toSet()
            for (scope in scopes) {
                if (scope.workspace == null && !contribution.isDetachedSupported) continue
                val names = contribution.specifications(scope).map { it.name }
                declared += names.size
                val missing = names.filterNot { it in catalog }
                assertTrue(missing.isEmpty(), "${contribution::class.simpleName} misses $missing in its catalog")
            }
        }
        assertTrue(declared > 0)
        app.profileSessions.close()
    }
}

/** Profile entry points of hosted tool contributions, for this test only. */
@ContributesTo(ProfileScope::class)
interface HostedToolCatalogAccessors {
    val toolContributions: Set<AgentToolContribution>
    val localWorkspaces: LocalWorkspaces
}
