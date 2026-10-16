package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioDirectoryPicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalStudioProjectsTest {
    @Test
    fun `selected directory registers once and only its opaque id leaves the effect`() = runTest {
        val registry = Registry()
        val projects = LocalStudioProjects(registry, Picker("/test/project"), Toggles(true))
        assertEquals("opaque", projects.choose())
        assertEquals(listOf("/test/project"), registry.registered)
    }

    @Test
    fun `cancelled picker never changes the catalog`() = runTest {
        val registry = Registry()
        assertNull(LocalStudioProjects(registry, Picker(null), Toggles(true)).choose())
        assertEquals(emptyList(), registry.registered)
    }

    @Test
    fun `disabled feature and unsupported platform never open the picker`() = runTest {
        val picker = Picker("/test/project")
        val projects = LocalStudioProjects(Registry(), picker, Toggles(false))
        assertEquals(false, projects.availability.first())
        assertFailsWith<IllegalStateException> { projects.choose() }
        assertEquals(0, picker.calls)
        val unsupported = LocalStudioProjects(Registry(), Picker(null, isAvailable = false), Toggles(true))
        assertEquals(false, unsupported.availability.first())
    }

    @Test
    fun `only folders inside a git working tree offer isolation and nothing is probed while it is off`() = runTest {
        val registry = Registry(
            projects = listOf(
                LocalWorkspace(WorkspaceRef("repo"), "repo"),
                LocalWorkspace(WorkspaceRef("folder"), "folder"),
            ),
        )
        val picker = Picker(null, repositories = setOf("/test/repo"))
        assertEquals(setOf("repo"), LocalStudioProjects(registry, picker, Toggles(true)).repositoryProjects.first())
        val probes = picker.probes
        assertEquals(emptySet(), LocalStudioProjects(registry, picker, Toggles(false)).repositoryProjects.first())
        assertEquals(probes, picker.probes)
    }

    @Test
    fun `registration error reaches the state machine failure handler`() = runTest {
        val registry = Registry(isFailing = true)
        assertFailsWith<IllegalArgumentException> {
            LocalStudioProjects(registry, Picker("/missing"), Toggles(true)).choose()
        }
    }

    private class Picker(
        private val result: String?,
        override val isAvailable: Boolean = true,
        private val repositories: Set<String> = emptySet(),
    ) : StudioDirectoryPicker {
        var calls = 0
        var probes = 0
        override suspend fun pick(): String? = result.also { calls++ }
        override suspend fun isRepository(directory: String): Boolean = (directory in repositories).also { probes++ }
    }

    private class Registry(
        private val isFailing: Boolean = false,
        private val projects: List<LocalWorkspace> = emptyList(),
    ) : LocalWorkspaces {
        override val isAvailable: Boolean = true
        val registered = mutableListOf<String>()
        override fun observe(): Flow<List<LocalWorkspace>> = flowOf(projects)
        override suspend fun resolve(ref: WorkspaceRef): String = "/test/${ref.value}"
        override suspend fun register(directory: String): LocalWorkspace {
            require(!isFailing) { "Folder unavailable" }
            registered += directory
            return LocalWorkspace(WorkspaceRef("opaque"), "project")
        }
    }

    private class Toggles(private val isEnabled: Boolean) : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // The project service requests only flag toggles.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }
}
