package io.aequicor.heartbeat.feature.computeruse.impl.ui

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerUseFrameDecoderTest {
    @Test
    fun `encoded previews decode through the injected CPU dispatcher`() = runTest {
        val scheduled = StandardTestDispatcher(testScheduler)
        var dispatches = 0
        val cpu = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                scheduled.dispatch(context, block)
            }
        }
        val decoder = ComputerUseFrameDecoder(object : DispatcherProvider {
            override val main = scheduled
            override val default = cpu
            override val io = scheduled
        })
        val content = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val image = assertNotNull(decoder.decode(content))
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertTrue(dispatches > 0)
        assertNull(decoder.decode(byteArrayOf(0)))
    }
}
