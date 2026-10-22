package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiComposerAssistsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = EngineTarget(PiEngineId, EngineBindingId("b"), ModelId("m"))
    private val workspace = WorkspaceRef("project")

    @Test
    fun `selected skills appear in mentions and slash commands alongside prompt templates`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".agents/skills/verify/SKILL.md", "---\nname: verify\ndescription: checks\n---\nbody")
        write(root, ".pi/prompts/review.md", "Review this")
        // Editable instruction documents are irrelevant to suggestion discovery and must never be opened.
        Files.createDirectory(root.resolve("AGENTS.md"))
        val items = assists(root).assists(target, workspace)
        assertEquals(listOf("verify "), items.filter { it.kind == EngineAssist.Kind.Skill }.map { it.insert })
        assertEquals(
            setOf("/skill:verify ", "/review "),
            items.filter { it.kind == EngineAssist.Kind.Command }.map { it.insert }.toSet(),
        )
    }

    @Test
    fun `project settings can disable slash skill commands while keeping mentions`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".agents/skills/verify/SKILL.md", "---\nname: verify\ndescription: checks\n---\nbody")
        write(root, ".pi/settings.json", """{"enableSkillCommands":false}""")
        val items = assists(root).assists(target, workspace)
        assertEquals(listOf(EngineAssist.Kind.Skill), items.map { it.kind })
        assertEquals("verify ", items.single().insert)
    }

    @Test
    fun `untrusted project is not resolved and excluded resources are not suggested`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".agents/skills/verify/SKILL.md", "---\nname: verify\ndescription: checks\n---\nbody")
        assertTrue(
            assists(
                root,
                trust = ProjectTrust.Content,
                resolve = { error("untrusted read") },
            ).assists(target, workspace).isEmpty(),
        )
        write(root, ".pi/settings.json", """{"skills":["!skills/**"]}""")
        assertTrue(assists(root).assists(target, workspace).isEmpty())
    }

    @Test
    fun `native precedence favors explicit profile skills and omits invalid skills`() = runTest {
        val root = temporary.newFolder().toPath()
        val home = temporary.newFolder().toPath()
        write(root, ".agents/skills/verify/SKILL.md", "---\nname: verify\ndescription: project\n---\nbody")
        write(home, "skills/verify/SKILL.md", "---\nname: verify\ndescription: profile\n---\nbody")
        write(root, ".agents/skills/missing/SKILL.md", "---\nname: missing\n---\nbody")
        write(root, ".agents/skills/broken/SKILL.md", "---\nname: broken\ndescription: [unclosed\n---")
        val items = assists(root, home = home).assists(target, workspace)
        assertEquals(setOf("verify"), items.map { it.label }.toSet())
        assertTrue(items.all { it.description == "profile" })
    }

    private fun assists(
        root: Path,
        trust: ProjectTrust = ProjectTrust.Trusted,
        home: Path = temporary.newFolder().toPath(),
        resolve: () -> Unit = {},
    ) = PiComposerAssists(
        DesktopPiConfigurator(home = { home }),
        EngineLaunchConfig {
            LaunchContext(
                config = EngineConfigValues(
                    mapOf(
                        PiDocuments.Settings to ConfigDocument.Json(
                            Json.parseToJsonElement("""{"skills":["skills"],"prompts":["prompts"]}""").jsonObject,
                        ),
                    ),
                ),
                trust = mapOf(workspace to trust),
                isConfigured = true,
            )
        },
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String): Nothing = error("unused")
            override suspend fun resolve(ref: WorkspaceRef): String {
                resolve()
                return root.toString()
            }
        },
        object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
        },
    )

    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
