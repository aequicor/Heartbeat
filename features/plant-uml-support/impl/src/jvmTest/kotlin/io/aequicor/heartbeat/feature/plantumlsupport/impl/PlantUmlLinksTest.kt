package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlLink
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.MAX_DIAGRAM_LINKS
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.MAX_LINK_METADATA_BYTES
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.plantUmlLinks
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerReply
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.readReply
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.writeReply
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlLinksTest {
    @Test
    fun `raw URL ampersands survive while tooltip entities decode once`() {
        val links = plantUmlLinks(
            area("https://example.com?a=1&b=2", "A &#38; B &#34;yes&#34;", "2,4,20,40"),
            2f,
            30f,
            30f,
        )
        assertEquals(listOf(PlantUmlLink(1f, 2f, 10f, 20f, "https://example.com?a=1&b=2", "A & B \"yes\"")), links)
    }

    @Test
    fun `malformed out of bounds and excessive maps cannot create hit targets`() {
        assertTrue(plantUmlLinks(area(coords = "-1,0,2,3"), 1f, 10f, 10f).isEmpty())
        assertTrue(plantUmlLinks(area(coords = "0,0,20,30"), 1f, 10f, 10f).isEmpty())
        assertTrue(plantUmlLinks(area(coords = "0,0,0,1"), 1f, 10f, 10f).isEmpty())
        assertTrue(plantUmlLinks("x".repeat(MAX_LINK_METADATA_BYTES + 1), 1f, 10f, 10f).isEmpty())
        assertEquals(MAX_DIAGRAM_LINKS, plantUmlLinks(area().repeat(MAX_DIAGRAM_LINKS + 1), 1f, 10f, 10f).size)
    }

    @Test
    fun `worker preserves link coordinates unicode and percent encoded destinations`() {
        val links = listOf(PlantUmlLink(1f, 2f, 5f, 7f, "heartbeat://diagram/send?text=%D0%94%D0%B0", "Отправить"))
        val image = PlantUmlResult.Image(byteArrayOf(1), 10f, 10f, 2f, links)
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(
                it,
            ).writeReply(PlantUmlWorkerReply(image, emptyList()))
        }
        val restored = DataInputStream(ByteArrayInputStream(bytes.toByteArray())).readReply(100)
        assertEquals(links, assertIs<PlantUmlResult.Image>(restored.result).links)
    }

    @Test
    fun `broken worker dimensions and excessive counts are rejected before allocation`() {
        fun frame(width: Float, count: Int): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                writeByte(1)
                writeInt(0)
                writeFloat(width)
                writeFloat(10f)
                writeFloat(1f)
                writeInt(count)
            }
        }.toByteArray()
        listOf(frame(Float.NaN, 0), frame(10f, MAX_DIAGRAM_LINKS + 1), frame(10f, -1)).forEach { bytes ->
            assertFailsWith<IOException> { DataInputStream(ByteArrayInputStream(bytes)).readReply(100) }
        }
    }

    private fun area(url: String = "https://example.com", tooltip: String = "", coords: String = "1,1,2,2") =
        """<area shape="rect" id="id1" href="$url" title="$tooltip" alt="" coords="$coords"/>"""
}
