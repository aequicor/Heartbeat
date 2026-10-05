package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.JvmPlantUmlEngine
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlDiagramType
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.plantUmlPreamble
import net.sourceforge.plantuml.TitledDiagram
import net.sourceforge.plantuml.dot.GraphvizRuntimeEnvironment
import net.sourceforge.plantuml.security.SecurityProfile
import net.sourceforge.plantuml.security.SecurityUtils
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmPlantUmlEngineTest {
    private val engine = JvmPlantUmlEngine()
    private val limits = PlantUmlLimits()

    private fun render(
        text: String,
        style: PlantUmlStyle = DarkStyle,
        scale: Float = 2f,
        limits: PlantUmlLimits = this.limits,
    ): PlantUmlResult {
        val source = PlantUmlSource.parse(text) ?: error("unsupported sample")
        return engine.render(source, plantUmlPreamble(style, scale), limits)
    }

    @Test
    fun `every in-process diagram type draws in light and dark themes`() {
        assertEquals(PlantUmlDiagramType.entries.toSet(), Samples.keys)
        Samples.forEach { (type, text) ->
            listOf(LightStyle, DarkStyle).forEach { style ->
                val result = render(text, style)
                assertIs<PlantUmlResult.Image>(result, "$type: $result")
            }
        }
    }

    @Test
    fun `images are sharp at the display scale on a transparent background`() {
        val image = assertIs<PlantUmlResult.Image>(render(Samples.getValue(PlantUmlDiagramType.Uml), scale = 2f))
        assertTrue(abs(image.scale - 2f) < 0.05f, "scale=${image.scale}")
        val bitmap = ImageIO.read(ByteArrayInputStream(image.png))
        assertTrue(abs(bitmap.width / image.scale - image.width) < 1f)
        assertEquals(0, bitmap.getRGB(0, 0) ushr 24, "corner alpha")
        val single = assertIs<PlantUmlResult.Image>(render(Samples.getValue(PlantUmlDiagramType.Uml), scale = 1f))
        assertTrue(abs(single.width - image.width) < 2f, "logical size does not depend on scale")
    }

    @Test
    fun `syntax errors point at the author's line`() {
        val error = assertIs<PlantUmlResult.SyntaxError>(
            render("@startuml\nA -> B\nthis is -> not -> valid ]]\n@enduml"),
        )
        assertEquals(3, error.line)
        assertTrue(error.message.isNotBlank())
        val wrapped = assertIs<PlantUmlResult.SyntaxError>(render("A -> B\nthis is -> not -> valid ]]"))
        assertEquals(2, wrapped.line)
    }

    @Test
    fun `files urls and environment variables stay out of reach`() {
        val file = assertIs<PlantUmlResult.SyntaxError>(render("@startuml\n!include /etc/hosts\nA -> B\n@enduml"))
        assertEquals(2, file.line)
        assertTrue("localhost" !in file.message)
        assertIs<PlantUmlResult.SyntaxError>(render("@startuml\n!include https://example.com/x.puml\nA -> B\n@enduml"))
        assertNull(PlantUmlSource.parse("@startuml\n!includeurl https://example.com/x.puml\nA -> B\n@enduml"))
        assertIs<PlantUmlResult.Image>(render("@startuml\nA -> B : %getenv(\"HOME\")\n@enduml"))
        assertEquals(SecurityProfile.SANDBOX, SecurityUtils.getSecurityProfile())
    }

    @Test
    fun `diagrams beyond the image limits are reported instead of cropped`() {
        val wide = "@startuml\n" + (1..40).joinToString("\n") { "P$it -> P${it + 1} : step $it" } + "\n@enduml"
        val result = render(wide, limits = limits.copy(maxSide = 400))
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.TooLarge), result)
    }

    @Test
    fun `layout pragmas cannot switch away from the in-process layout`() {
        listOf("!pragma layout dot", "!pragma layout vizjs", "skinparam layout dot").forEach { pragma ->
            val result = render("@startuml\n$pragma\nclass A\nclass B\nA --> B\n@enduml")
            assertIs<PlantUmlResult.Image>(result, pragma)
        }
        assertTrue(TitledDiagram.FORCE_SMETANA)
    }

    @Test
    fun `service diagrams are not drawn and never launch graphviz`() {
        val marker = File.createTempFile("heartbeat-dot", ".marker").apply { delete() }
        val dot = File.createTempFile("heartbeat-dot", ".sh").apply {
            writeText("#!/bin/sh\necho \"$@\" >> '${marker.absolutePath}'\necho 'dot - graphviz version 2.43.0'\n")
            setExecutable(true)
        }
        val previous = System.setProperty(GRAPHVIZ_DOT, dot.absolutePath)
        try {
            listOf("version", "testdot", "license", "listfonts").forEach { service ->
                listOf(service, "@startuml\n$service\n@enduml").forEach {
                    val result = render(it)
                    assertTrue(result == Unsupported || result is PlantUmlResult.SyntaxError, "$it: $result")
                }
            }
            assertIs<PlantUmlResult.Image>(render("!pragma layout dot\nclass A"))
            assertTrue(!marker.exists(), "dot was launched: ${marker.takeIf(File::exists)?.readText()}")
            assertTrue(GraphvizRuntimeEnvironment.getInstance().getenvGraphvizDot().orEmpty().contains('\u0000'))
        } finally {
            if (previous == null) System.clearProperty(GRAPHVIZ_DOT) else System.setProperty(GRAPHVIZ_DOT, previous)
            dot.delete()
            marker.delete()
        }
    }

    @Test
    fun `standard library procedures stay available`() {
        val c4 = "@startuml\n!include <C4/C4_Container>\nPerson(user, \"User\")\nSystem(studio, \"Studio\")\n" +
            "Rel(user, studio, \"asks\")\n@enduml"
        assertIs<PlantUmlResult.Image>(render(c4))
    }

    @Test
    fun `an error in the author's own style keeps its line`() {
        val result = render("@startuml\n<style>\nroot {\n  FontColor\n}\n</style>\nA -> B\n@enduml")
        assertEquals(2, assertIs<PlantUmlResult.SyntaxError>(result).line)
    }

    @Test
    fun `an error inside the generated theme is not blamed on the author`() {
        val source = PlantUmlSource.parse("@startuml\nA -> B\n@enduml") ?: error("unsupported")
        val result = engine.render(source, listOf("this is not a theme line"), limits)
        assertNull(assertIs<PlantUmlResult.SyntaxError>(result).line)
    }

    private companion object {
        const val GRAPHVIZ_DOT = "GRAPHVIZ_DOT"
        val Unsupported = PlantUmlResult.Unsupported
        val LightStyle = PlantUmlStyle(
            text = 0xFF242426.toInt(),
            secondaryText = 0xFF6B6B70.toInt(),
            line = 0xFF7C7C7D.toInt(),
            fill = 0xFFE5E5E6.toInt(),
            border = 0xFF7C7C7D.toInt(),
            accentFill = 0xFFEFECFA.toInt(),
            fontSize = 14f,
            isDark = false,
        )
        val DarkStyle = PlantUmlStyle(
            text = 0xFFEEEEF0.toInt(),
            secondaryText = 0xFFB0B0B4.toInt(),
            line = 0xFF9D9D9F.toInt(),
            fill = 0xFF3C3C3E.toInt(),
            border = 0xFF9D9D9F.toInt(),
            accentFill = 0xFF3B3650.toInt(),
            fontSize = 14f,
            isDark = true,
        )
        val Samples = mapOf(
            PlantUmlDiagramType.Uml to "@startuml\nactor User\nUser -> Studio : ask\nnote right : cached\n@enduml",
            PlantUmlDiagramType.MindMap to "@startmindmap\n* Heartbeat\n** Chat\n** Engines\n@endmindmap",
            PlantUmlDiagramType.Wbs to "@startwbs\n* Release\n** Design\n** Build\n@endwbs",
            PlantUmlDiagramType.Gantt to "@startgantt\n[Design] lasts 3 days\n[Build] lasts 5 days\n" +
                "[Build] starts at [Design]'s end\n@endgantt",
            PlantUmlDiagramType.Json to "@startjson\n{ \"fence\": \"plantuml\", \"lines\": [1, 2] }\n@endjson",
            PlantUmlDiagramType.Yaml to "@startyaml\nfence: plantuml\nclosed: true\n@endyaml",
            PlantUmlDiagramType.Salt to "@startsalt\n{\n  Login | \"name\"\n  [Cancel] | [OK]\n}\n@endsalt",
            PlantUmlDiagramType.Ebnf to "@startebnf\nrule = \"a\" | \"b\";\n@endebnf",
            PlantUmlDiagramType.Regex to "@startregex\n[a-z]+@[a-z]+\n@endregex",
            PlantUmlDiagramType.Chen to "@startchen\nentity PERSON {\n  Name\n}\n@endchen",
        )
    }
}
