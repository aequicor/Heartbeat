package io.aequicor.heartbeat.feature.projectconfig.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLayoutTest {
    @Test
    fun `paths are relative visible and bounded`() {
        assertTrue(ConfigPath.isValid("manifest.json"))
        assertTrue(ConfigPath.isValid("harnesses/review/harness.json"))
        assertTrue(ConfigPath.isValid("learning/Code-Style_2.md"))

        listOf(
            "",
            "/harnesses/a.json",
            "harnesses//a.json",
            "harnesses/../secrets",
            "harnesses/./a",
            ".heartbeat/a",
            "harnesses/.DS_Store",
            "harnesses\\a.json",
            "harnesses/a.",
            "harnesses/a b.md",
            "a/b/c/d/e/f/g/h/i",
            "a".repeat(ConfigLimits.PATH_CHARS + 1),
        ).forEach { assertFalse(ConfigPath.isValid(it), it) }
        assertNull(ConfigPath.parse("../x"))
    }

    @Test
    fun `path knows its section and the rest`() {
        val path = ConfigPath.of("harnesses", "review", "skills", "tests.md")

        assertEquals("harnesses", path.section)
        assertEquals("review/skills/tests.md", path.inSection)
        assertEquals("", ConfigPath("manifest.json").inSection)
        assertEquals(4, path.segments.size)
    }

    @Test
    fun `unit files stay inside the section in canonical order`() {
        val ref = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
        val unit = ConfigUnit.of(
            ref,
            listOf(file("harnesses/review/z.md"), file("harnesses/review/harness.json")),
        )

        assertEquals(listOf("harnesses/review/harness.json", "harnesses/review/z.md"), unit.files.map { it.path.value })
        assertFailsWith<IllegalArgumentException> { ConfigUnit(ref, listOf(file("learning/review.md"))) }
        assertFailsWith<IllegalArgumentException> { ConfigUnit(ref, listOf(file("harnesses"))) }
        assertFailsWith<IllegalArgumentException> {
            ConfigUnit(ref, listOf(file("harnesses/review/a.md"), file("harnesses/review/a.md")))
        }
    }

    @Test
    fun `texts are never shown`() {
        val file = ConfigFile(ConfigPath("learning/a.md"), "secret instruction")

        assertFalse("secret" in file.toString())
        assertEquals(18, file.bytes)
        assertFalse("secret" in ConfigUnit.of(ConfigUnitRef(ConfigSectionId.Learning, "a"), listOf(file)).toString())
    }

    @Test
    fun `summary knows code and invalid checks need problems`() {
        assertFalse(ConfigSummary("t", contents = listOf(ConfigContent(ConfigContentKind.Skill, "a"))).hasCode)
        assertTrue(ConfigSummary("t", contents = listOf(ConfigContent(ConfigContentKind.Workflow, "w"))).hasCode)
        assertFailsWith<IllegalArgumentException> { ConfigCheck.Invalid(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ConfigSectionId("Harnesses") }
    }

    private fun file(path: String) = ConfigFile(ConfigPath(path), "x")
}
