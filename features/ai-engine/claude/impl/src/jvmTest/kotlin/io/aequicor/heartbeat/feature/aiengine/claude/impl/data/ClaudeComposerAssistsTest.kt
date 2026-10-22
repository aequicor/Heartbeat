package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AssistDirectory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectAssistReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeComposerAssistsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = WorkspaceRef("project")
    private val target = EngineTarget(ClaudeEngine.Id, EngineBindingId("b"), ModelId("m"))

    @Test
    fun `literal import strips hooks and permissions and rejects interpolation including in paths`() {
        val imported = assertNotNull(
            literalClaudeContent("---\nhooks: unsafe\nallowed-tools: Bash\n---\nDo work", "/project"),
        )
        assertFalse("hooks:" in imported)
        assertFalse("allowed-tools" in imported)
        assertTrue(imported.endsWith("Do work"))
        assertNull(literalClaudeContent("!`echo unsafe`", "/project"))
        assertNull(literalClaudeContent("Do work", "/project!`echo unsafe`"))
    }

    @Test
    fun `nested commands use explicit manifest names and cleanup removes operation files`() = runTest {
        val root = temporary.newFolder().toPath()
        val path = root.resolve(".claude/commands/frontend/check.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, "---\nhooks: ignored\n---\nCheck frontend")
        var trust = ProjectTrust.Trusted
        val source = source(root, { trust })
        assertEquals("/heartbeat-project:frontend:check ", source.assists(target, workspace).single().insert)
        val plugin = assertNotNull(source.prepare(workspace))
        val directory = Path.of(plugin.directory)
        val manifest = Json.parseToJsonElement(
            Files.readString(directory.resolve(".claude-plugin/plugin.json")),
        ).jsonObject
        val command = manifest.getValue("commands").jsonObject.getValue("frontend:check").jsonObject
        val entry = directory.resolve(command.getValue("source").jsonPrimitive.content)
        assertTrue(Files.readString(entry).endsWith("Check frontend"))
        plugin.close()
        assertFalse(Files.exists(directory))
        trust = ProjectTrust.Content
        assertTrue(source.assists(target, workspace).isEmpty())
        assertNull(source.prepare(workspace))
    }

    private fun source(root: Path, trust: () -> ProjectTrust) = DesktopClaudeComposerAssists(
        object : ProjectAssistReader by ProjectAssistReader.None {
            override suspend fun read(engine: EngineId, workspace: WorkspaceRef?, directories: List<AssistDirectory>) =
                listOf(
                    EngineAssist(
                        ".claude/commands:frontend/check.md",
                        "frontend:check",
                        "",
                        "/heartbeat-project:frontend:check ",
                        EngineAssist.Kind.Command,
                    ),
                )
        },
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String): Nothing = error("unused")
            override suspend fun resolve(ref: WorkspaceRef) = root.toString()
        },
        EngineLaunchConfig { LaunchContext(trust = mapOf(workspace to trust())) },
        object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
        },
    )
}
