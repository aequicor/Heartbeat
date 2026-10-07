package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class PiComposerAssistsTest {
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("m"))
    private val workspace = WorkspaceRef("project")

    @Test
    fun `skills come from both project skill directories with frontmatter names`() = runTest {
        val root = Files.createTempDirectory("pi-skills")
        write(root, ".agents/skills/verify/SKILL.md", frontmatter("verify", "run checks"))
        write(root, ".pi/skills/utf8/SKILL.md", frontmatter(null, "encoding helper"))
        write(root, ".agents/skills/empty/nested.txt", "not a skill")

        val assists = assists(root).assists(target, workspace)

        assertEquals(
            listOf(
                EngineAssist("skill:verify", "verify", "run checks", "verify ", EngineAssist.Kind.Skill),
                EngineAssist("skill:utf8", "utf8", "encoding helper", "utf8 ", EngineAssist.Kind.Skill),
            ),
            assists,
        )
    }

    @Test
    fun `a session without a project or an unknown workspace has no assists`() = runTest {
        val root = Files.createTempDirectory("pi-skills")
        write(root, ".agents/skills/verify/SKILL.md", frontmatter("verify", ""))
        val source = assists(root)
        assertEquals(emptyList(), source.assists(target, null))
        assertEquals(emptyList(), source.assists(target, WorkspaceRef("unknown")))
    }

    private fun assists(root: Path) = PiComposerAssists(
        object : io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = kotlinx.coroutines.flow.flowOf(emptyList<io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace>())
            override suspend fun register(directory: String) = throw UnsupportedOperationException()
            override suspend fun resolve(ref: WorkspaceRef): String? = ref.takeIf { it == workspace }?.let { root.toString() }
        },
        object : DispatcherProvider {
            override val main = Dispatchers.Default
            override val default = Dispatchers.Default
            override val io = Dispatchers.Default
        },
    )

    private fun frontmatter(name: String?, description: String): String {
        val nameLine = name?.let { "name: $it" } ?: ""
        return "---\n$nameLine\ndescription: $description\n---\n\nBody of the skill.\n"
    }

    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
