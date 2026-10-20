package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningMachineKey
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEnabled
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigMachineKey
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectConfigIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val project = Files.createTempDirectory("heartbeat-project").toFile()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
        project.deleteRecursively()
    }

    @Test
    fun `project configuration is a registered toggle on by default with harness, interface and learning sections`() =
        runTest {
            assertTrue(ProjectConfigEnabled in toggles.toggleControl.registered)
            assertTrue(ProjectConfigEnabled.default)
            val profile = app.profileSessions.open(ProfileId("config-off"))
            val sections = (profile.graph as TestProjectConfigAccessors).configSections.map { it.id }.toSet()
            assertEquals(
                setOf(ConfigSectionId.Harnesses, ConfigSectionId.Interfaces, ConfigSectionId.Learning),
                sections,
            )
            app.profileSessions.close()
        }

    @Test
    fun `a lesson in the project folder is trusted into the profile and its edit is written back`() = runTest {
        toggles.toggleControl.setOverride(ProjectConfigEnabled, true)
        toggles.toggleControl.setOverride(AgentLearningEnabled, true)
        val learning = File(project, ".heartbeat/learning").apply { mkdirs() }
        File(learning, "style.json").writeText("""{"kind":"general","title":"Style"}""")
        File(learning, "style.md").writeText("Use tabs.\n")
        val profile = app.profileSessions.open(ProfileId("config"))
        val accessors = profile.graph as TestProjectConfigAccessors
        val ref = withContext(Dispatchers.Default) { accessors.localWorkspaces.register(project.absolutePath) }.ref

        val proposal = awaitConfig(accessors) { state ->
            state.proposals.singleOrNull()
        }
        // Import uses the user's exact digest confirmation even when no review model is configured.
        trust(accessors, ProjectConfigIntent.Internal.Trust(proposal.id, proposal.digest))
        val lesson = withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT) {
                accessors.machines.observe(AgentLearningMachineKey).filterNotNull().flatMapLatest { it.state }
                    .first { state -> (state as? AgentLearningState.Ready)?.instructions?.isNotEmpty() == true }
            }
        }.let { (it as AgentLearningState.Ready).instructions.single() }
        assertEquals(ref, lesson.project)
        assertEquals("Use tabs.", lesson.content)

        accessors.machines.send(
            AgentLearningMachineKey,
            AgentLearningIntent.Public.Edit(lesson.id, lesson.title, lesson.description, "Use spaces.", 1),
        )
        val body = File(learning, "style.md")
        withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT) {
                while (body.readText() != "Use spaces.\n") delay(POLL)
            }
        }
        assertTrue(File(project, ".heartbeat/manifest.json").exists())
        app.profileSessions.close()
    }

    /** Trust is Internal and only project-config:impl holds the full machine; the cast stands in for its screen. */
    private suspend fun trust(accessors: TestProjectConfigAccessors, intent: ProjectConfigIntent.Internal.Trust) {
        @Suppress("UNCHECKED_CAST") // The registry reference dispatches every intent of its spec.
        val machine = checkNotNull(accessors.machines.find(ProjectConfigMachineKey))
            as MachineRef<ProjectConfigState, ProjectConfigIntent, ProjectConfigOutput>
        machine.send(intent)
    }

    private suspend fun <T : Any> awaitConfig(
        accessors: TestProjectConfigAccessors,
        select: (ProjectConfigState.Ready) -> T?,
    ): T = withContext(Dispatchers.Default) {
        withTimeout(TIMEOUT) {
            accessors.machines.observe(ProjectConfigMachineKey).filterNotNull().flatMapLatest { it.state }
                .first { state -> (state as? ProjectConfigState.Ready)?.let(select) != null }
                .let { checkNotNull(select(it as ProjectConfigState.Ready)) }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val POLL = 100L
    }
}

@ContributesTo(ProfileScope::class)
interface TestProjectConfigAccessors {
    val configSections: Set<ConfigSection>
    val localWorkspaces: LocalWorkspaces
    val machines: MachineRegistry
}
