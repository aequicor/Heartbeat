package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningTools
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun `profile dispatcher resolves virtual workspaces through the profile registry`() = runTest {
        toggles.toggleControl.setOverride(PlanningModeEnabled, true)
        val profile = app.profileSessions.open(ProfileId("virtual-tools")).graph as HostedToolCatalogAccessors
        val workspace = WorkspaceRef("sandbox-tool-catalog-test")
        assertNotNull(profile.localWorkspaces.virtual(workspace))
        // Session creation asks this dispatcher for declarations and instructions before starting the adapter.
        val scope = AgentToolScope(workspace)
        val names = profile.agentTools.specifications(scope).map { it.name }
        assertTrue(names.containsAll(PlanningTools.ALL))
        assertTrue(names.none { it in setOf("run_command", "shell", "bash") })
        profile.agentTools.instructions(scope)
        val missing = assertFailsWith<IllegalStateException> {
            profile.agentTools.specifications(AgentToolScope(WorkspaceRef("sandbox-missing-tool-catalog-test")))
        }
        assertEquals("VirtualWorkspaceUnavailable", missing.message)
        app.profileSessions.close()
        app.closeAndAwaitStorages()
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
        // Stop periodic app/profile services before runTest drains its virtual scheduler.
        app.closeAndAwaitStorages()
    }
}

/** Profile entry points of hosted tool contributions, for this test only. */
@ContributesTo(ProfileScope::class)
interface HostedToolCatalogAccessors {
    val agentTools: ProfileAgentTools
    val toolContributions: List<AgentToolContribution>
    val localWorkspaces: LocalWorkspaces
}

/** Resolves one synthetic sandbox so the test exercises the production registry and dispatcher DI bindings. */
@Inject
@ContributesIntoSet(ProfileScope::class)
class ToolCatalogVirtualWorkspaceProvider : VirtualWorkspaceProvider {
    override suspend fun find(ref: WorkspaceRef): VirtualWorkspace? =
        if (ref.value == "sandbox-tool-catalog-test") ToolCatalogVirtualWorkspace(ref) else null
}

private class ToolCatalogVirtualWorkspace(override val ref: WorkspaceRef) : VirtualWorkspace {
    override val processDirectory = "unused-service-directory"
    override suspend fun revision() = 0L
    override suspend fun paths() = emptyList<String>()
    override suspend fun read(path: String): String? = null
    override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long =
        error("Unexpected file write")
    override suspend fun move(expectedRevision: Long, path: String, destination: String): Long =
        error("Unexpected file move")
}
