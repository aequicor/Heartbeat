package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbDiagramFailure
import io.aequicor.heartbeat.ds.components.HbDiagramLanguage
import io.aequicor.heartbeat.ds.components.HbDiagramRequest
import io.aequicor.heartbeat.ds.components.HbDiagramResult
import io.aequicor.heartbeat.ds.components.HbDiagramStyle
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlantUmlDiagramRendererTest {
    private val style = HbDiagramStyle(
        text = Color.White,
        secondaryText = Color.LightGray,
        line = Color.Gray,
        fill = Color.DarkGray,
        border = Color.Gray,
        accentFill = Color.Blue,
        fontSize = 14f,
        isDark = true,
    )
    private val request = HbDiagramRequest(HbDiagramLanguage.PlantUml, "A -> B", style, density = 2f)

    private fun adapter(result: PlantUmlResult, requests: MutableList<PlantUmlRequest> = mutableListOf()) =
        PlantUmlDiagramRenderer(
            object : PlantUmlRenderer {
                override val availability: Flow<Boolean> = flowOf(true)

                override suspend fun render(request: PlantUmlRequest): PlantUmlResult {
                    requests += request
                    return result
                }
            },
            Dispatchers.Unconfined,
        )

    @Test
    fun `a drawn PNG is decoded and shown at its logical size with the row's theme`() = runTest {
        val requests = mutableListOf<PlantUmlRequest>()
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val result = adapter(PlantUmlResult.Image(png, 40f, 20f, 2f), requests).render(request)
        val image = assertIs<HbDiagramResult.Image>(result)
        assertEquals(80, image.bitmap.width)
        assertEquals(DpSize(40.dp, 20.dp), image.size)
        val sent = requests.single()
        assertEquals("A -> B", sent.source)
        assertEquals(2f, sent.scale)
        assertEquals(Color.White.toArgb(), sent.style.text)
    }

    @Test
    fun `errors failures and unsupported sources keep their meaning`() = runTest {
        assertEquals(
            HbDiagramResult.SyntaxError(2, "Syntax Error?"),
            adapter(PlantUmlResult.SyntaxError(2, "Syntax Error?")).render(request),
        )
        PlantUmlFailure.entries.forEach { reason ->
            assertEquals(
                HbDiagramResult.Failed(HbDiagramFailure.valueOf(reason.name)),
                adapter(PlantUmlResult.Failed(reason)).render(request),
            )
        }
        assertEquals(HbDiagramResult.Unsupported, adapter(PlantUmlResult.Unsupported).render(request))
    }

    @Test
    fun `undecodable bytes become an internal failure`() = runTest {
        val result = adapter(PlantUmlResult.Image(byteArrayOf(1, 2, 3), 10f, 10f, 1f)).render(request)
        assertEquals(HbDiagramResult.Failed(HbDiagramFailure.Internal), result)
    }
}
