package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessContentTest {
    private val skill = HarnessItem.Skill(ItemId("skill"), ItemName("guide"), "Index description", "Private skill body")
    private val template = HarnessItem.Template(
        ItemId("template"),
        ItemName("draft"),
        "Template description",
        "Hello {{name}}! {{name}}",
        setOf("name"),
    )

    @Test
    fun `qualified names select one active item while ambiguous bare names never pick the first match`() {
        val first = harness.copy(name = HarnessName("alpha"), items = listOf(skill, template))
        val second = first.copy(id = HarnessId("second"), name = HarnessName("beta"))
        val content = HarnessContent(listOf(second, first))
        assertEquals(skill.body, content.skill("alpha/guide"))
        assertEquals(skill.body, content.skill("beta/guide"))
        assertFailsWith<IllegalArgumentException> { content.skill("guide") }
        assertFailsWith<IllegalArgumentException> { content.skill("unknown/guide") }
        assertFailsWith<IllegalArgumentException> { content.skill("alpha/draft") }
        assertFailsWith<IllegalArgumentException> { content.render("alpha/guide", emptyMap()) }
    }

    @Test
    fun `disabled harnesses and items contribute neither context nor resolution`() {
        val first = harness.copy(items = listOf(skill, template.copy(isEnabled = false)))
        val second = first.copy(id = HarnessId("other"), name = HarnessName("other"), isEnabled = false)
        val content = HarnessContent(listOf(second, first))
        assertEquals(skill.body, content.skill("guide"))
        assertFailsWith<IllegalArgumentException> { content.render("draft", mapOf("name" to "value")) }
        assertEquals(listOf(first.id), content.block().markers.map { it.harness })
        assertFalse(content.block().text.contains("other"))
        assertFalse(content.block().text.contains("draft"))
    }

    @Test
    fun `context includes instructions and descriptions but omits on-demand bodies and executable code`() {
        val instruction = HarnessItem.Instruction(ItemId("note"), ItemName("note"), "First line\nSecond line")
        val source = harness.copy(items = listOf(skill, template, instruction, code.copy(source = "SECRET_CODE")))
        val block = HarnessContent(listOf(source)).block()
        assertTrue(block.text.contains("First line\nSecond line"))
        assertTrue(block.text.contains("${source.name.value}/guide — Index description"))
        assertTrue(block.text.contains("${source.name.value}/draft — Template description"))
        assertFalse(block.text.contains(skill.body))
        assertFalse(block.text.contains(template.body))
        assertFalse(block.text.contains("SECRET_CODE"))
        assertFalse(block.isTruncated)
        assertFalse(block.toString().contains("First line"))
    }

    @Test
    fun `context trimming is a bounded prefix with an explicit full-context marker`() {
        val items = (0..8).map {
            HarnessItem.Instruction(ItemId("i$it"), ItemName("i$it"), "$it".repeat(2_000))
        } + HarnessItem.Skill(ItemId("last"), ItemName("zzlast"), "small trailing line", "body")
        val block = HarnessContent(listOf(harness.copy(items = items))).block()
        assertTrue(block.isTruncated)
        assertTrue(block.text.length <= HARNESS_CONTEXT_CHARS)
        assertTrue(block.text.endsWith("вызови harness_context."))
        assertFalse(block.text.contains("small trailing line"))
        assertTrue(block.text.contains("0".repeat(2_000)))
        assertFalse(block.text.contains("8".repeat(2_000)))
    }

    @Test
    fun `empty context has no phantom delivered markers and item order does not change the block`() {
        assertEquals("", HarnessContent(emptyList()).block().text)
        assertTrue(HarnessContent(emptyList()).block().markers.isEmpty())
        val one = HarnessContent(listOf(harness.copy(items = listOf(template, skill)))).block()
        val two = HarnessContent(listOf(harness.copy(items = listOf(skill, template)))).block()
        assertEquals(one, two)
    }

    @Test
    fun `template substitutions are literal and never recursively evaluate inserted placeholders`() {
        val content = HarnessContent(listOf(harness.copy(items = listOf(template))))
        assertEquals("Hello {{other}}! {{other}}", content.render("draft", mapOf("name" to "{{other}}")))
        assertEquals("Hello $1\\! $1\\", content.render("draft", mapOf("name" to "$1\\")))
        assertEquals("Hello ! ", content.render("draft", mapOf("name" to "")))
    }

    @Test
    fun `unknown missing and malformed template arguments fail without echoing content`() {
        val content = HarnessContent(listOf(harness.copy(items = listOf(template))))
        assertFailsWith<IllegalArgumentException> { content.render("draft", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { content.render("draft", mapOf("secret" to "private")) }
        listOf("{{secret}}", "{{name", "name}}", "{{ name }}").forEach { body ->
            val invalid = template.copy(body = body)
            val error = assertFailsWith<IllegalArgumentException> {
                renderHarnessTemplate(invalid, mapOf("name" to "private"))
            }
            assertFalse(error.message.orEmpty().contains(body))
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test
    fun `expanded template quota counts every repeated value`() {
        val repeated = template.copy(body = "{{name}}{{name}}")
        val half = "x".repeat(MAX_RENDERED_TEMPLATE_CHARS / 2)
        assertEquals(MAX_RENDERED_TEMPLATE_CHARS, renderHarnessTemplate(repeated, mapOf("name" to half)).length)
        assertFailsWith<IllegalArgumentException> { renderHarnessTemplate(repeated, mapOf("name" to half + "x")) }
    }
}
