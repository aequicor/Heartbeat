package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
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
            "---\nname: \u00a0valid\ndescription: non ASCII name\n---\nBody",
            "---\nname:\u00a0valid\ndescription: valid description\n---\nBody",
            "---\nname: valid\ndescription:\u00a0valid description\n---\nBody",
            "---\nname:valid\ndescription: valid description\n---\nBody",
            "---\nname: valid\ndescription:valid description\n---\nBody",
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

    @Test
    fun `a sandbox lists the tree it snapshots under its source project trust`() = runTest {
        val sandbox = sandbox(
            source = workspace,
            ".claude/commands/check.md" to "---\ndescription: checks\n---\nbody",
            ".agents/skills/verify/SKILL.md" to "---\nname: verify\ndescription: tests\n---\nbody",
            ".agents/skills/verify/notes.md" to "not a skill",
            "src/Main.kt" to "fun main() = Unit",
        )
        val items = sandboxReader(sandbox, ProjectTrust.Trusted).read(engine, sandbox.ref, roots)
        assertEquals(listOf("check", "verify"), items.map { it.label })
        assertEquals(listOf(".claude/commands:check.md", ".agents/skills:verify/SKILL.md"), items.map { it.id })
        assertEquals(listOf("/check ", "verify "), items.map { it.insert })
    }

    @Test
    fun `a sandbox of an untrusted or unknown source contributes nothing`() = runTest {
        val files = arrayOf(".claude/commands/check.md" to "body")
        assertEquals(
            emptyList(),
            sandboxReader(sandbox(workspace, *files), ProjectTrust.Content).read(engine, WorkspaceRef("sandbox-1"), roots),
        )
        assertEquals(
            emptyList(),
            sandboxReader(sandbox(null, *files), ProjectTrust.Trusted).read(engine, WorkspaceRef("sandbox-1"), roots),
        )
    }

    @Test
    fun `unreadable sandbox entries are skipped and literal mode still omits shell expansion`() = runTest {
        val sandbox = sandbox(
            workspace,
            ".claude/commands/link.md" to null,
            ".claude/commands/dynamic.md" to "!`echo hidden`",
            ".claude/commands/plain.md" to "plain",
        )
        val items = sandboxReader(sandbox, ProjectTrust.Trusted)
            .read(engine, sandbox.ref, roots.map { it.copy(isLiteralOnly = true) })
        assertEquals(listOf("plain"), items.map { it.label })
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

    /** A null file body stands for an entry the sandbox refuses to read, such as a link. */
    private fun sandbox(source: WorkspaceRef?, vararg files: Pair<String, String?>) = object : VirtualWorkspace {
        override val ref = WorkspaceRef("sandbox-1")
        override val processDirectory = "unused"
        override suspend fun sourceProject() = source
        override suspend fun revision() = 1L
        override suspend fun paths() = files.map { it.first }.sorted()
        override suspend fun read(path: String) =
            files.first { it.first == path }.second ?: error("SandboxLinkUnsupported")
        override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long = error("unused")
        override suspend fun move(expectedRevision: Long, path: String, destination: String): Long = error("unused")
    }

    private fun sandboxReader(sandbox: VirtualWorkspace, trust: ProjectTrust) = DesktopProjectAssistReader(
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String): Nothing = error("unused")
            override suspend fun resolve(ref: WorkspaceRef): String = error("a sandbox has no project directory")
            override suspend fun virtual(ref: WorkspaceRef) = sandbox.takeIf { ref == it.ref }
        },
        EngineLaunchConfig { LaunchContext(trust = mapOf(workspace to trust, sandbox.ref to ProjectTrust.Trusted)) },
        dispatchers,
    )

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
