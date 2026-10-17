package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessIdsTest {
    @Test
    fun `process id storage key is always a valid data key`() {
        val longest = ProcessId("a".repeat(ProcessLimits.ID_CHARS))
        assertTrue(DATA_KEY.matches(longest.storageKey))
        assertTrue(longest.storageKey.length <= DATA_KEY_CHARS)
        assertEquals("process_p1", ProcessId("p1").storageKey)
        assertEquals("process_7_x", ProcessId("7_x").storageKey)
    }

    @Test
    fun `process id rejects anything but lowercase letters digits and underscore`() {
        for (bad in listOf("", "P1", "p-1", "p 1", "p.1", "p/1", "é", "a".repeat(ProcessLimits.ID_CHARS + 1))) {
            assertFailsWith<IllegalArgumentException>(bad) { ProcessId(bad) }
        }
    }

    @Test
    fun `stage name is a lowercase slug`() {
        assertEquals("plan_2", StageName("plan_2").value)
        assertEquals(ProcessLimits.STAGE_CHARS, StageName("a".repeat(ProcessLimits.STAGE_CHARS)).value.length)
        for (bad in listOf("", "2plan", "Plan", "plan-2", "plan 2", "a".repeat(ProcessLimits.STAGE_CHARS + 1))) {
            assertFailsWith<IllegalArgumentException>(bad) { StageName(bad) }
        }
    }

    @Test
    fun `subject sha is a full lowercase object id`() {
        SubjectSha("a".repeat(40))
        SubjectSha("0123456789abcdef".repeat(4))
        for (bad in listOf(
            "",
            "abc1234",
            "A".repeat(40),
            "g".repeat(40),
            "a".repeat(39),
            "a".repeat(41),
            "a".repeat(63),
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { SubjectSha(bad) }
        }
    }

    @Test
    fun `subject sha shows only a short prefix`() {
        assertEquals("SubjectSha(abcdef0)", SubjectSha("abcdef0" + "1".repeat(33)).toString())
    }

    @Test
    fun `references accept visible ascii and nothing that needs escaping`() {
        ExecutorRef(ExecutorKind.Workflow, "run_01HZ-a.b:c/d?e=f&g#h%20")
        ResultRef("comment", "a".repeat(ProcessLimits.REF_CHARS))
        for (bad in listOf("", " ", "a b", " a", "a\n", "a\"b", "a\\b", "é", "a".repeat(ProcessLimits.REF_CHARS + 1))) {
            assertFailsWith<IllegalArgumentException> { ExecutorRef(ExecutorKind.Helper, bad) }
            assertFailsWith<IllegalArgumentException> { ResultRef("comment", bad) }
        }
    }

    @Test
    fun `result kind is a lowercase slug`() {
        for (bad in listOf("", "Comment", "1comment", "a-b", "a".repeat(ProcessLimits.STAGE_CHARS + 1))) {
            assertFailsWith<IllegalArgumentException>(bad) { ResultRef(bad, "x") }
        }
    }

    @Test
    fun `references never print their identifiers`() {
        val executor = ExecutorRef(ExecutorKind.Human, "secret-person-1")
        val result = ResultRef("comment", "secret-comment-1")
        assertFalse("secret" in executor.toString())
        assertFalse("secret" in result.toString())
        assertTrue("Human" in executor.toString())
        assertTrue("comment" in result.toString())
    }

    private companion object {
        private const val DATA_KEY_CHARS = 64
        private val DATA_KEY = Regex("[a-z][a-z0-9_]{0,63}")
    }
}
