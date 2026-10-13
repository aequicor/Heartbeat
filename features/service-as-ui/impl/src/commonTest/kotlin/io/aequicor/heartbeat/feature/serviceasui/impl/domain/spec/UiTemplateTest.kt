package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPathSegment.Index
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPathSegment.Key
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTemplatePart.Binding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTemplatePart.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UiTemplateTest {
    @Test
    fun `plain text has no bindings`() {
        val template = parsed("Pull requests")

        assertEquals(listOf(Text("Pull requests")), template.parts)
        assertNull(template.single)
        assertEquals(emptyList(), template.paths)
    }

    @Test
    fun `a whole-string binding keeps its path`() {
        val template = parsed("{{ data.prs.items[0].title }}")

        val path = UiPath(UiPathRoot.Data, listOf(Key("prs"), Key("items"), Index(0), Key("title")))
        assertEquals(path, template.single)
        assertEquals("prs", path.head)
        assertEquals("data.prs.items[0].title", path.toString())
    }

    @Test
    fun `mixed text keeps literal parts around bindings`() {
        val template = parsed("PR #{{item.number}}: {{item.title}}!")

        assertEquals(
            listOf(
                Text("PR #"),
                Binding(UiPath(UiPathRoot.Item, listOf(Key("number")))),
                Text(": "),
                Binding(UiPath(UiPathRoot.Item, listOf(Key("title")))),
                Text("!"),
            ),
            template.parts,
        )
        assertNull(template.single)
        assertEquals(2, template.paths.size)
    }

    @Test
    fun `escaped braces stay literal`() {
        assertEquals(listOf(Text("{{name}} is {{x}}")), parsed("""\{{name}} is \{{x}}""").parts)
        assertEquals(
            listOf(Text("{{"), Binding(UiPath(UiPathRoot.Index))),
            parsed("""\{{{{index}}""").parts,
        )
    }

    @Test
    fun `every root is recognised`() {
        for (root in UiPathRoot.entries) {
            assertEquals(UiPath(root), parseUiPath(root.key))
        }
        assertEquals(UiPath(UiPathRoot.Status, listOf(Key("prs"), Key("loading"))), parseUiPath("status.prs.loading"))
        assertEquals(UiPath(UiPathRoot.State, listOf(Key("my-key"))), parseUiPath("state.my-key"))
    }

    @Test
    fun `invalid bindings are rejected`() {
        for (text in listOf(
            "{{data.prs",
            "{{}}",
            "{{ }}",
            "{{unknown.x}}",
            "{{data..x}}",
            "{{data[x]}}",
            "{{a {{b}}",
        )) {
            assertIs<UiTemplateParse.Invalid>(parseUiTemplate(text), text)
        }
        for (path in listOf("", "data.", "data.1x", "data[1234567]", "data.x y", ".data")) {
            assertNull(parseUiPath(path), path)
        }
    }

    private fun parsed(text: String): UiTemplate = assertIs<UiTemplateParse.Parsed>(parseUiTemplate(text)).template
}
