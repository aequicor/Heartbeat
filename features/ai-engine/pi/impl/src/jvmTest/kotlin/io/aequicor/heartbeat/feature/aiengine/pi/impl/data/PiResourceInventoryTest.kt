package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiResourceInventoryTest {
    private val root = Files.createTempDirectory("pi-inventory").toRealPath()
    private val home = root.resolve("home").createDirectories()
    private val project = root.resolve("project").createDirectories()
    private val configurator = DesktopPiConfigurator({ home })

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `profile files need a plain settings entry before masks can select them`() = runTest {
        write(home, "extensions/a.ts")
        write(home, "extensions/old.ts")
        write(home, "skills/check/SKILL.md", "---\nname: check\ndescription: Run checks\n---\nBody")
        val config = settings("""{"extensions":["extensions","!extensions/old.ts"],"skills":["skills/**"]}""")

        val result = configurator.resources(config, null, ProjectTrust.Undecided)

        assertEquals(
            mapOf(
                "extensions/a.ts" to ResourceState.Enabled,
                "extensions/old.ts" to ResourceState.Excluded,
                "skills/check/SKILL.md" to ResourceState.NotListed,
            ),
            result.resources.associate { it.path to it.state },
        )
        assertEquals("Run checks", result.resources.last().description)
        assertTrue(result.resources.all { it.origin == ResourceOrigin.Profile })
    }

    @Test
    fun `exact include overrides glob exclusion and exact exclusion wins last`() = runTest {
        write(home, "skills/check/SKILL.md")
        val included = settings("""{"skills":["skills","!check","+skills/check"]}""")
        val excluded = settings("""{"skills":["skills","!check","+skills/check","-skills/check"]}""")
        assertEquals(
            ResourceState.Enabled,
            configurator.resources(included, null, ProjectTrust.Content).resources.single().state,
        )
        assertEquals(
            ResourceState.Excluded,
            configurator.resources(excluded, null, ProjectTrust.Content).resources.single().state,
        )
    }

    @Test
    fun `project trust and launch flags match content explicitly loaded by Heartbeat`() = runTest {
        write(project, ".pi/extensions/a.ts")
        write(project, ".pi/skills/check/SKILL.md")
        write(project, ".pi/prompts/review.md")
        write(project, ".pi/settings.json", """{"skills":["!check"]}""")
        val config = EngineConfigValues()
        val content = configurator.resources(config, project.toString(), ProjectTrust.Content).resources
        assertEquals(ResourceState.NeedsTrust, content.first { it.kind == ResourceKind.Extension }.state)
        assertTrue(content.filter { it.kind != ResourceKind.Extension }.all { it.state == ResourceState.Enabled })
        val trusted = configurator.resources(config, project.toString(), ProjectTrust.Trusted).resources
        assertEquals(ResourceState.Enabled, trusted.first { it.kind == ResourceKind.Extension }.state)
        assertEquals(ResourceState.Excluded, trusted.first { it.kind == ResourceKind.Skill }.state)
        assertEquals(ResourceState.Enabled, trusted.first { it.kind == ResourceKind.Prompt }.state)
    }

    @Test
    fun `discovery respects entry points skill roots agents mode and ignored files`() = runTest {
        write(project, ".pi/extensions/package/index.ts")
        write(project, ".pi/extensions/package/helper.ts")
        write(project, ".pi/extensions/ignored.ts")
        write(project, ".pi/extensions/.gitignore", "ignored.ts")
        write(project, ".pi/skills/parent/SKILL.md")
        write(project, ".pi/skills/parent/child/SKILL.md")
        write(project, ".agents/skills/loose.md")
        write(project, ".agents/skills/group/readme.md")
        write(project, ".pi/prompts/nested/template.md")
        val paths = configurator.resources(EngineConfigValues(), project.toString(), ProjectTrust.Trusted)
            .resources.map { it.path }
        assertEquals(
            listOf(".pi/extensions/package/index.ts", ".pi/skills/parent/SKILL.md", ".agents/skills/group/readme.md"),
            paths,
        )
    }

    @Test
    fun `external paths and links are never read and internal cycles terminate`() = runTest {
        write(root, "private/SKILL.md", "---\nname: secret\n---")
        home.resolve("skills").createDirectories()
        Files.createSymbolicLink(home.resolve("skills/outside"), root.resolve("private"))
        Files.createSymbolicLink(home.resolve("skills/cycle"), home.resolve("skills"))
        write(home, "skills/inside/SKILL.md")
        val config = settings("""{"skills":["skills","../private","~/private"]}""")
        val result = configurator.resources(config, null, ProjectTrust.Undecided)
        assertEquals(listOf("skills/inside/SKILL.md"), result.resources.map { it.path })
        assertEquals(2, result.external)
        assertFalse(result.isTruncated)
    }

    @Test
    fun `resource traversal reports its bound instead of claiming completeness`() = runTest {
        val many = home.resolve("prompts").createDirectories()
        repeat(4100) { many.resolve("p$it.md").writeText("") }
        val result = configurator.resources(settings("""{"prompts":["prompts"]}"""), null, ProjectTrust.Undecided)
        assertTrue(result.isTruncated)
        assertTrue(result.resources.size <= 4096)
    }

    @Test
    fun `malformed manifests fall back to index and untrusted content ignores invalid settings`() = runTest {
        write(project, ".pi/extensions/pkg/package.json", "{")
        write(project, ".pi/extensions/pkg/index.ts")
        write(project, ".pi/settings.json", "{")
        write(project, ".pi/prompts/.hidden.md")
        write(project, ".pi/prompts/.gitignore", "*.md")
        val result = configurator.resources(EngineConfigValues(), project.toString(), ProjectTrust.Content)
        assertEquals(
            listOf(".pi/extensions/pkg/index.ts", ".pi/prompts/.hidden.md"),
            result.resources.map { it.path },
        )
        assertEquals(ResourceState.Enabled, result.resources.last().state)
    }

    @Test
    fun `anchored ignore applies only at its own root`() = runTest {
        write(home, "prompts/.ignore", "/foo.md")
        write(home, "prompts/foo.md")
        write(home, "prompts/nested/foo.md")
        val result = configurator.resources(settings("""{"prompts":["prompts"]}"""), null, ProjectTrust.Content)
        assertEquals(listOf("prompts/nested/foo.md"), result.resources.map { it.path })
    }

    @Test
    fun `glob matching handles literal invalid classes and does not backtrack exponentially`() {
        assertFalse(piResourceGlob("**/test.ts", "mytest.ts"))
        assertFalse(piResourceGlob("a/**/b", "a/xb"))
        assertTrue(piResourceGlob("[z-a]", "[z-a]"))
        assertTrue(piResourceGlob("[!]", "[!]"))
        assertTrue(piResourceGlob("skills/**/SKILL.md", "skills/SKILL.md"))
        assertTrue(piResourceGlob("**/*.{ts,js}", "nested/a.ts"))
        assertFalse(piResourceGlob("*a".repeat(30) + "b", "a".repeat(80)))
    }

    @Test
    fun `direct settings entries share the traversal budget`() {
        val files = PiResourceFiles(home)
        repeat(4100) { files.safe(home.resolve("explicit$it.ts")) }
        assertTrue(files.isTruncated)
    }

    @Test
    fun `package manifest entries cannot bypass the shared traversal bound`() = runTest {
        val entries = (0..4099).map { "e$it.ts" }
        entries.forEach { write(home, "extensions/pkg/$it") }
        val jsonEntries = entries.joinToString(",") { "\"$it\"" }
        write(home, "extensions/pkg/package.json", "{\"pi\":{\"extensions\":[$jsonEntries]}}")
        val result = configurator.resources(
            settings("""{"extensions":["extensions/pkg"]}"""),
            null,
            ProjectTrust.Undecided,
        )
        assertTrue(result.isTruncated)
        assertTrue(result.resources.size <= 4096)
    }

    private fun settings(text: String) = EngineConfigValues(
        mapOf(PiDocuments.Settings to ConfigDocument.Json(Json.parseToJsonElement(text) as JsonObject)),
    )

    private fun write(base: Path, relative: String, text: String = "") {
        val file = base.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }
}
