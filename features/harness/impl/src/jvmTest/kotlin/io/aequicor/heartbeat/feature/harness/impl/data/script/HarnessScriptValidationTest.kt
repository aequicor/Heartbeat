package io.aequicor.heartbeat.feature.harness.impl.data.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessScriptValidationTest {
    @Test
    fun `println spelling including escaped identifier is rejected but literal text is allowed`() {
        assertEquals(1, validateHarnessSource("println(1)").size)
        assertEquals(1, validateHarnessSource("`println`(1)").size)
        assertTrue(validateHarnessSource("val text = \"println @Serializable suspend\" // println()").isEmpty())
    }

    @Test
    fun `serialization and top level suspend declarations are rejected`() {
        assertEquals(1, validateHarnessSource("@Serializable class Data").size)
        assertEquals(1, validateHarnessSource("suspend fun work() {}").size)
        assertTrue(validateHarnessSource("class Nested { suspend fun work() {} }").isEmpty())
    }

    @Test
    fun `diagnostic count and printable forms do not expose source`() {
        val diagnostics = validateHarnessSource(List(25) { "println(\"private-value\")" }.joinToString("\n"))
        assertEquals(20, diagnostics.size)
        assertTrue(diagnostics.none { "private-value" in it.toString() })
        assertEquals(1, diagnostics.first().line)
        assertEquals(1, diagnostics.first().column)
    }
}
