package io.aequicor.heartbeat.feature.planningmode.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PlanDocumentTest {
    private val text = """
        # Feature

        Intro.

        ## Steps

        1. One
        2. Two

        ### Details

        Nested.

        ## Risks

        None.
    """.trimIndent()

    @Test
    fun `replace swaps the whole text`() {
        assertEquals("New", applyEdit(text, PlanEdit.Replace("New\n\n")))
    }

    @Test
    fun `section edit replaces the body up to the next heading of the same level`() {
        val edited = applyEdit(text, PlanEdit.Section("## Steps", "1. Only"))
        assertEquals("# Feature\n\nIntro.\n\n## Steps\n\n1. Only\n\n## Risks\n\nNone.", edited)
    }

    @Test
    fun `section edit of a nested heading keeps the parent`() {
        val edited = applyEdit(text, PlanEdit.Section("### Details", "Changed."))
        assertTrue("### Details\n\nChanged.\n\n## Risks" in edited)
        assertTrue("2. Two" in edited)
    }

    @Test
    fun `missing section is appended and blank text removes a section`() {
        assertTrue(applyEdit(text, PlanEdit.Section("## Tests", "jvmTest")).endsWith("None.\n\n## Tests\n\njvmTest"))
        assertEquals(
            "# Feature\n\nIntro.\n\n## Steps\n\n1. One\n2. Two\n\n### Details\n\nNested.",
            applyEdit(text, PlanEdit.Section("## Risks", " ")),
        )
        assertEquals(text, applyEdit(text, PlanEdit.Section("## Absent", "")))
        assertEquals("## Steps\n\nOne", applyEdit("", PlanEdit.Section("## Steps", "One")))
    }

    @Test
    fun `headings inside code fences are not sections`() {
        val fenced = "## Steps\n\n```\n## Risks\n```\n\n## Risks\n\nReal."
        assertEquals("## Steps\n\nDone\n\n## Risks\n\nReal.", applyEdit(fenced, PlanEdit.Section("## Steps", "Done")))
        assertEquals("Real", planTitle("```\n# Code\n```\n# Real"))
    }

    @Test
    fun `title is the first heading`() {
        assertEquals("Feature", planTitle(text))
        assertNull(planTitle("No heading\n#hashtag"))
    }

    @Test
    fun `block carries the rules of the mode and the status`() {
        val plan = plan(PlanMode.Planning, PlanStatus.Approved, "# Plan")
        val block = requireNotNull(renderPlanBlock(plan))
        assertTrue("Planning mode is on" in block)
        assertTrue("revision 3, approved by the user" in block)
        assertTrue("<plan>\n# Plan\n</plan>" in block)
        assertTrue("implements the plan" in requireNotNull(renderPlanBlock(plan.copy(mode = PlanMode.Implementing))))
        assertNull(renderPlanBlock(plan.copy(mode = PlanMode.Detached)))
    }

    @Test
    fun `long plan is cut with a pointer to plan_read`() {
        val block = requireNotNull(renderPlanBlock(plan(PlanMode.Planning, PlanStatus.Draft, "x".repeat(50)), 10))
        assertTrue("xxxxxxxxxx\n\n[The plan continues: call ${PlanningTools.READ}" in block)
        assertTrue(
            "(the plan is empty)" in requireNotNull(renderPlanBlock(plan(PlanMode.Planning, PlanStatus.Draft, ""))),
        )
    }

    @Test
    fun `chat ids are safe directory names`() {
        assertTrue(isValidPlanChat("0f8e2c1a-3b4d-4e5f-8a9b-0c1d2e3f4a5b"))
        listOf("", "../x", "a/b", "a b", "ä", "x".repeat(65)).forEach { assertTrue(!isValidPlanChat(it), it) }
    }

    @Test
    fun `plan text never reaches toString`() {
        val plan = plan(PlanMode.Planning, PlanStatus.Draft, "secret")
        assertTrue("secret" !in plan.toString())
        assertTrue("secret" !in PlanEdit.Replace("secret").toString())
        assertTrue("secret" !in PlanEdit.Section("## A", "secret").toString())
    }

    private fun plan(mode: PlanMode, status: PlanStatus, text: String) =
        Plan(PlanChat("c1"), mode, status, revision = 3, text = text, updatedAt = Instant.fromEpochSeconds(0))
}
