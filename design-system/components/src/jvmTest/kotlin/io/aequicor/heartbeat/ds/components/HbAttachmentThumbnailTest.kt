package io.aequicor.heartbeat.ds.components

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HbAttachmentThumbnailTest {
    @Test
    fun `large images are sampled to at most 128 pixels without retaining the original encoding`() {
        val original = imageFixture(1600, 900)
        val preview = createAttachmentImageThumbnail(original)
        Image.makeFromEncoded(preview).use { image ->
            assertTrue(image.width <= 128 && image.height <= 128)
        }
        assertTrue(preview.size <= 256 * 1024)
    }

    @Test
    fun `WebP uses the bounded Skia codec when the JDK has no image reader`() {
        val webp = Image.makeFromEncoded(imageFixture(1600, 900)).use { image ->
            requireNotNull(image.encodeToData(EncodedImageFormat.WEBP)).use { it.bytes }
        }
        val preview = createAttachmentImageThumbnail(webp)
        Image.makeFromEncoded(preview).use { image ->
            assertTrue(image.width <= 128 && image.height <= 128)
        }
        assertTrue(preview.size <= 256 * 1024)
        val bitmap = ImageIO.read(ByteArrayInputStream(preview))
        val sky = java.awt.Color(bitmap.getRGB(bitmap.width / 8, bitmap.height / 8))
        val ground = java.awt.Color(bitmap.getRGB(bitmap.width / 2, bitmap.height * 5 / 6))
        assertTrue(abs(sky.blue - 146) <= 20 && abs(sky.red - 62) <= 20)
        assertTrue(abs(ground.green - 133) <= 20 && abs(ground.red - 37) <= 20)
    }

    @Test
    fun `oversized encoded sources are rejected before entering a platform decoder`() {
        assertFailsWith<IllegalArgumentException> { createAttachmentImageThumbnail(ByteArray(10 * 1024 * 1024 + 1)) }
    }
}

internal fun imageFixture(width: Int = 256, height: Int = 128): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = java.awt.Color(62, 91, 146)
        graphics.fillRect(0, 0, width, height)
        graphics.color = java.awt.Color(247, 197, 83)
        graphics.fillOval(width / 4, height / 5, width / 2, height / 2)
        graphics.color = java.awt.Color(37, 133, 112)
        graphics.fillRect(0, height * 2 / 3, width, height / 3)
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}
