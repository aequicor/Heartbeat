package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AssistDirectory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopProjectAssistReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = WorkspaceRef("project")
    private val engine = EngineId("claude")
    private val roots = listOf(
        AssistDirectory(".claude/commands", EngineAssist.Kind.Command, isSkill = false, prefix = "/"),
        AssistDirectory(".agents/skills", EngineAssist.Kind.Skill, isSkill = true, prefix = ""),
    )

    @Test
    fun `untrusted projects are rejected before resolving their directory`() = runTest {
        val reader = reader(temporary.newFolder().toPath(), ProjectTrust.Undecided) { error("must not resolve") }
        assertEquals(emptyList(), reader.read(engine, workspace, roots))
    }

    @Test
    fun `trusted metadata gives concise names and engine insertion without evaluating bodies`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".claude/commands/check.md", "---\ndescription: 'Run checks'\n---\n!`never-execute`")
        write(root, ".agents/skills/verify/SKILL.md", "---\nname: \"verify\"\ndescription: tests\n---\nbody")
        val items = reader(root).read(engine, workspace, roots)
        assertEquals(listOf("check", "verify"), items.map { it.label })
        assertEquals(listOf("/check ", "verify "), items.map { it.insert })
        assertEquals("Run checks", items.first().description)
    }

    @Test
    fun `linked roots and linked metadata files never leave the project`() = runTest {
        val root = temporary.newFolder().toPath()
        val outside = temporary.newFolder().toPath()
        write(outside, "secret.md", "---\ndescription: secret\n---")
        Files.createDirectory(root.resolve(".claude"))
        Files.createSymbolicLink(root.resolve(".claude/commands"), outside)
        write(root, ".agents/skills/linked/placeholder", "")
        Files.createSymbolicLink(root.resolve(".agents/skills/linked/SKILL.md"), outside.resolve("secret.md"))
        assertEquals(emptyList(), reader(root).read(engine, workspace, roots))
    }

    @Test
    fun `hidden skills and names containing paths cannot produce executable references`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".agents/skills/hidden/SKILL.md", "---\nname: hidden\nuser-invocable: false\n---")
        write(root, ".agents/skills/safe/SKILL.md", "---\nname: ../../outside\n---")
        val items = reader(root).read(engine, workspace, roots)
        assertEquals(listOf("safe"), items.map { it.label })
        assertTrue(items.none { ".." in it.insert })
    }

    @Test
    fun `a changed trust decision discards an in flight listing`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".claude/commands/check.md", "text")
        var trust = ProjectTrust.Trusted
        val reader = DesktopProjectAssistReader(
            workspaces(root) { trust = ProjectTrust.Content },
            EngineLaunchConfig { LaunchContext(trust = mapOf(workspace to trust)) },
            dispatchers,
        )
        assertEquals(emptyList(), reader.read(engine, workspace, roots))
    }

    @Test
    fun `nested command names follow paths and literal mode omits shell expansion`() = runTest {
        val root = temporary.newFolder().toPath()
        write(root, ".claude/commands/front/check.md", "---\nname: ignored\n---\ncheck front")
        write(root, ".claude/commands/back/check.md", "check back")
        write(root, ".claude/commands/dynamic.md", "!`echo hidden`")
        val items = reader(root).read(engine, workspace, roots.map { it.copy(isLiteralOnly = true) })
        assertEquals(listOf("back:check", "front:check"), items.map { it.label })
        assertEquals(listOf("/back:check ", "/front:check "), items.map { it.insert })
    }

    @Test
    fun `native skills omit missing invalid and ambiguous metadata without renaming`() = runTest {
        val root = temporary.newFolder().toPath()
        val invalid = listOf(
            "Body without metadata",
            "---\nname: missing\n---\nBody",
            "---\ndescription: missing name\n---\nBody",
            "---\nname: missing\ndescription: unclosed",
            "---\nname: ../../outside\ndescription: unsafe name\n---",
            "---\nname: false\ndescription: non string name\n---",
            "---\nname: valid\ndescription: [unclosed\n---",
            "---\nname: valid\nname: duplicate\ndescription: ambiguous\n---",
            "---\nname: \"\"\ndescription: empty name\n---",
            "---\nname: valid\ndescription: \" \"\n---",
            "---\nname: valid\ndescription: description\nmetadata:\n  nested: unsupported\n---",
            "---\nname: valid\ndescription: >\n    first\n  invalid dedent\n---",
        )
        invalid.forEachIndexed { index, body -> write(root, ".agents/skills/fallback$index/SKILL.md", body) }
        val items = reader(root).read(engine, workspace, nativeRoots)
        assertTrue(items.isEmpty(), "invalid native entries must not become folder-named mentions: $items")
    }

    @Test
    fun `native skills preserve quoted strings and accept block descriptions`() = runTest {
        val root = temporary.newFolder().toPath()
        write(
            root,
            ".agents/skills/folder/SKILL.md",
            "---\nname: 'actual' # native name\ndescription: \"Checks, including # tags\" # comment\n---\nBody",
        )
        write(
            root,
            ".agents/skills/block/SKILL.md",
            "---\nname: block\ndescription: >-\n  Run checks\n  and report results\n---\nBody",
        )
        val items = reader(root).read(engine, workspace, nativeRoots)
        assertEquals(listOf("actual", "block"), items.map { it.label })
        assertEquals(listOf("\$actual ", "\$block "), items.map { it.insert })
        assertEquals(listOf("Checks, including # tags", "Run checks and report results"), items.map { it.description })
    }

    private val nativeRoots = listOf(
        AssistDirectory(".agents/skills", EngineAssist.Kind.Skill, true, "$", isNativeSkillMetadataRequired = true),
    )

    private fun reader(root: Path, trust: ProjectTrust = ProjectTrust.Trusted, onResolve: () -> Unit = {}) =
        DesktopProjectAssistReader(
            workspaces(root, onResolve),
            EngineLaunchConfig { LaunchContext(trust = mapOf(workspace to trust)) },
            dispatchers,
        )

    private fun workspaces(root: Path, onResolve: () -> Unit) = object : LocalWorkspaces {
        override val isAvailable = true
        override fun observe() = flowOf(emptyList<LocalWorkspace>())
        override suspend fun register(directory: String): Nothing = error("unused")
        override suspend fun resolve(ref: WorkspaceRef): String {
            onResolve()
            return root.toString()
        }
    }

    private fun write(root: Path, relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }
}
