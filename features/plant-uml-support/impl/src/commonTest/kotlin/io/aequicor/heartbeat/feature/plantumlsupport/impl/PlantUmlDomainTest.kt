package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.PlantUmlResultCache
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlDiagramType
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.plantUmlPreamble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlantUmlDomainTest {
    @Test
    fun `a fence without a start directive is wrapped and keeps the author's line numbers`() {
        val source = PlantUmlSource.parse("A -> B\r\nB -> C")!!
        assertEquals("@startuml\nA -> B\nB -> C\n@enduml", source.text)
        assertEquals(PlantUmlDiagramType.Uml, source.type)
        assertEquals(2, source.fenceLine(2))
        assertNull(source.fenceLine(0))
    }

    @Test
    fun `start directives pick the diagram type and unknown ones are not drawn`() {
        assertEquals(
            PlantUmlDiagramType.MindMap,
            PlantUmlSource.parse("' notes\n@startmindmap\n* a\n@endmindmap")?.type,
        )
        assertEquals(PlantUmlDiagramType.Json, PlantUmlSource.parse("@StartJson\n{}\n@endjson")?.type)
        assertEquals(3, PlantUmlSource.parse("' notes\n@startuml\nA -> B\n@enduml")!!.fenceLine(2))
        assertNull(PlantUmlSource.parse("@startdot\ndigraph { a -> b }\n@enddot"))
        assertNull(PlantUmlSource.parse("@startditaa\n+--+\n@endditaa"))
        assertNull(PlantUmlSource.parse("@startuml\n  !pragma layout ELK\nA -> B\n@enduml"))
        val source = PlantUmlSource.parse("' notes\n@startuml\nA -> B\n@enduml")!!
        assertTrue(source.isPreambleLine(1))
        assertTrue(!source.isPreambleLine(2))
    }

    @Test
    fun `the result cache keeps drawings and syntax errors within its byte budget`() {
        val cache = PlantUmlResultCache(maxEntries = 8, maxBytes = 10)
        val style = PlantUmlStyle(0, 0, 0, 0, 0, 0, fontSize = 14f, isDark = false)
        fun request(source: String) = PlantUmlRequest(source, style, scale = 1f)
        val small = PlantUmlResult.Image(ByteArray(6), 1f, 1f, 1f)
        cache.put(request("small"), small)
        cache.put(request("error"), PlantUmlResult.SyntaxError(2, "Syntax Error?"))
        cache.put(request("busy"), PlantUmlResult.Failed(PlantUmlFailure.Busy))
        assertSame(small, cache[request("small")])
        assertEquals(PlantUmlResult.SyntaxError(2, "Syntax Error?"), cache[request("error")])
        assertNull(cache[request("busy")], "transient failures are not cached")
        cache.put(request("large"), PlantUmlResult.Image(ByteArray(8), 1f, 1f, 1f))
        assertNull(cache[request("small")], "evicted by bytes")
        assertTrue(cache[request("large")] is PlantUmlResult.Image)
    }

    @Test
    fun `the preamble sets layout resolution and theme without any source text`() {
        val style = PlantUmlStyle(
            text = 0xFFEEEEF0.toInt(),
            secondaryText = 0xFFB0B0B4.toInt(),
            line = 0xFF9D9D9F.toInt(),
            fill = 0x803C3C3E.toInt(),
            border = 0xFF9D9D9F.toInt(),
            accentFill = 0xFF3B3650.toInt(),
            fontSize = 60f,
            isDark = true,
        )
        val preamble = plantUmlPreamble(style, scale = 2f)
        assertEquals("!pragma layout smetana", preamble.first())
        assertTrue("skinparam dpi 192" in preamble)
        assertTrue("  FontColor #eeeef0" in preamble)
        assertTrue("  BackGroundColor #3c3c3e80" in preamble)
        assertTrue("  FontSize 32" in preamble)
        assertTrue("  BackGroundColor transparent" in preamble)
        assertTrue("skinparam dpi 96" in plantUmlPreamble(style, scale = 1f))
    }
}
