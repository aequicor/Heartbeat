package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class DesktopProjectFileIndexTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workspace = WorkspaceRef("project")

    @Test
    fun `the walk lists files, skips build trees and ranks names first`() = runTest {
        val root = temporary.newFolder("files").toPath()
        write(root, "README.md", "hello")
        write(root, "src/common/Main.kt", "code")
        write(root, "build/generated/Big.class", "ignored")
        write(root, ".git/config", "ignored")

        val index = index(root)
        val found = index.search(workspace, "read", 10)

        assertEquals(listOf("README.md"), found.map { it.relativePath })
        val main = index.search(workspace, "Main", 10)
        assertEquals(listOf("src/common/Main.kt"), main.map { it.relativePath })
        val all = index.search(workspace, "", 100)
        assertEquals(listOf("README.md", "src/common/Main.kt"), all.map { it.relativePath }.sorted())
    }

    @Test
    fun `an unknown workspace resolves to no files`() = runTest {
        val root = temporary.newFolder("files").toPath()
        write(root, "README.md", "hello")
        assertEquals(emptyList(), index(root).search(WorkspaceRef("other"), "read", 10))
        assertEquals(emptyList(), index(root).search(null, "read", 10))
    }

    @Test
    fun `directories at the depth limit are not offered as files`() {
        val root = temporary.newFolder("files-depth").toPath()
        write(root, "nested/hidden.md", "file")
        assertEquals(emptyList(), walkProjectFiles(root, EmptyCoroutineContext, depthLimit = 1))
    }

    @Test
    fun `directory visits consume the scan budget`() {
        val root = temporary.newFolder("files-budget").toPath()
        write(root, "nested/readme.md", "file")
        assertEquals(emptyList(), walkProjectFiles(root, EmptyCoroutineContext, entryLimit = 2))
        assertEquals(
            listOf("nested/readme.md"),
            walkProjectFiles(root, EmptyCoroutineContext, entryLimit = 3).map { it.relativePath },
        )
    }

    @Test
    fun `cancellation during a scan propagates without finishing the tree`() {
        val root = temporary.newFolder("files-cancel").toPath()
        write(root, "readme.md", "file")
        val job = Job()
        var checks = 0
        val context = object : CoroutineContext by job {
            override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
                if (++checks == 3) job.cancel()
                return job[key]
            }
        }
        assertFailsWith<CancellationException> { walkProjectFiles(root, context) }
    }

    @Test
    fun `a symlink to an external file is never offered`() {
        val root = temporary.newFolder("files-link").toPath()
        val external = temporary.newFile("external.md").toPath()
        try {
            Files.createSymbolicLink(root.resolve("readme.md"), external)
        } catch (e: FileSystemException) {
            // Windows requires developer mode or a symlink privilege; explicitly skip when the host denies it.
            Log.tag("ProjectFileIndexTest").w(e) { "Symbolic links unavailable in this test environment" }
            assumeNoException(e)
        }
        assertEquals(emptyList(), walkProjectFiles(root, EmptyCoroutineContext))
    }

    @Test
    fun `skills match their declared names and command listings exclude ordinary files`() = runTest {
        val root = temporary.newFolder("skills").toPath()
        repeat(20) { write(root, "file$it.md", "ordinary") }
        write(root, ".agents/skills/folder/SKILL.md", "---\nname: open-pr\ndescription: Open a draft\n---\nBody")
        val index = index(root)
        val row = index.search(workspace, "open", 16).single()
        assertEquals(ProjectSkill("open-pr", "Open a draft"), row.skill)
        assertEquals(listOf(row), index.search(workspace, "", 16, skillsOnly = true))
    }

    @Test
    fun `literal metadata supports quotes and folded descriptions but requires a complete header`() {
        assertEquals(
            ProjectSkill("review", "Review changes carefully"),
            projectSkillMetadata(
                "---\nname: 'review'\ndescription: >-\n  Review changes\n  carefully\n---\nDo the work",
            ),
        )
        assertEquals(null, projectSkillMetadata("---\nname: review\ndescription: incomplete"))
        assertEquals(null, projectSkillMetadata("---\nname: review\ndescription: Hidden\nuser-invocable: false\n---"))
        assertEquals(null, projectSkillMetadata("---\nname: invalid name\ndescription: Invalid\n---"))
    }

    @Test
    fun `skill bodies are read only from plain skill files declaring the linked name`() = runTest {
        val root = temporary.newFolder("bodies").toPath()
        val body = "---\nname: open-pr\ndescription: Open a draft\n---\nSteps"
        write(root, ".agents/skills/open pr/SKILL.md", body)
        write(root, ".agents/skills/large/SKILL.md", "---\nname: large\ndescription: Big\n---\n" + "x".repeat(70_000))
        write(root, "outside/SKILL.md", "---\nname: outside\ndescription: Out\n---\n")
        val index = index(root)
        assertEquals(body, index.readSkill(workspace, ".agents/skills/open pr/SKILL.md", "open-pr"))
        assertEquals(null, index.readSkill(workspace, ".agents/skills/open pr/SKILL.md", "other"))
        assertEquals(null, index.readSkill(workspace, ".agents/skills/large/SKILL.md", "large"))
        assertEquals(null, index.readSkill(workspace, ".agents/skills/../../outside/SKILL.md", "outside"))
        assertEquals(null, index.readSkill(null, ".agents/skills/open pr/SKILL.md", "open-pr"))
        val linked = root.resolve(".claude/skills/linked")
        Files.createDirectories(linked.parent)
        try {
            Files.createSymbolicLink(linked, root.resolve(".agents/skills/open pr"))
        } catch (e: FileSystemException) {
            Log.tag("ProjectFileIndexTest").w(e) { "Symbolic links unavailable in this test environment" }
            assumeNoException(e)
        }
        assertEquals(null, index.readSkill(workspace, ".claude/skills/linked/SKILL.md", "open-pr"))
    }

    @Test
    fun `skill bodies require the real spelling of their path`() = runTest {
        val root = temporary.newFolder("spelling").toPath()
        write(root, ".agents/skills/review/SKILL.md", "---\nname: review\ndescription: Review\n---\n")
        assumeTrue(Files.exists(root.resolve(".agents/skills/REVIEW/SKILL.md")))
        assertEquals(null, index(root).readSkill(workspace, ".agents/skills/REVIEW/SKILL.md", "review"))
    }

    @Test
    fun `a sandbox offers only its skill files and reads their bodies from the sandbox tree`() = runTest {
        val skill = "---\nname: open-pr\ndescription: Open a PR\n---\nOpen a pull request"
        val files = mapOf(
            ".claude/skills/open-pr/SKILL.md" to skill,
            ".claude/skills/linked/SKILL.md" to null,
            "README.md" to "hello",
        )
        val sandbox = object : VirtualWorkspace {
            override val ref = WorkspaceRef("sandbox-1")
            override val processDirectory = "unused"
            override suspend fun revision() = 1L
            override suspend fun paths() = files.keys.sorted()
            override suspend fun read(path: String) = files.getValue(path) ?: error("SandboxLinkUnsupported")
            override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long =
                error("unused")
            override suspend fun move(expectedRevision: Long, path: String, destination: String): Long = error("unused")
        }
        val index = DesktopProjectFileIndex(
            object : LocalWorkspaces {
                override val isAvailable = true
                override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
                override suspend fun register(directory: String): LocalWorkspace = throw UnsupportedOperationException()
                override suspend fun resolve(ref: WorkspaceRef): String = error("a sandbox has no project directory")
                override suspend fun virtual(ref: WorkspaceRef) = sandbox.takeIf { ref == it.ref }
            },
            TestDispatchers,
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
            },
        )
        val found = index.search(sandbox.ref, "", 10)
        assertEquals(listOf(".claude/skills/open-pr/SKILL.md"), found.map { it.relativePath })
        assertEquals("open-pr", found.single().skill?.name)
        assertEquals(skill, index.readSkill(sandbox.ref, ".claude/skills/open-pr/SKILL.md", "open-pr"))
        assertEquals(null, index.readSkill(sandbox.ref, ".claude/skills/open-pr/SKILL.md", "other"))
        assertEquals(null, index.readSkill(sandbox.ref, "README.md", "open-pr"))
    }

    private fun index(root: Path) = DesktopProjectFileIndex(
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
            override suspend fun register(directory: String): LocalWorkspace = throw UnsupportedOperationException()
            override suspend fun resolve(ref: WorkspaceRef): String? =
                ref.takeIf { it == workspace }?.let { root.toString() }
        },
        TestDispatchers,
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        },
    )

    private object TestDispatchers : io.aequicor.heartbeat.core.common.DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.Default
    }

    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
