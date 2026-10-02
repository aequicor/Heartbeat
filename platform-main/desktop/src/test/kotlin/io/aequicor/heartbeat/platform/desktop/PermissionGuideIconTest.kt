package io.aequicor.heartbeat.platform.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PermissionGuideIconTest {
    @Test
    fun `only the largest bounded bitmap is exported`() {
        assertEquals(
            2,
            permissionGuideIconIndex(listOf(1024L to 1024L, 32L to 32L, 128L to 128L, 64L to 64L)),
        )
    }

    @Test
    fun `oversized tall vector and absent representations use a generic icon`() {
        assertNull(permissionGuideIconIndex(listOf(1024L to 1024L, 128L to 256L, 0L to 0L)))
        assertNull(permissionGuideIconIndex(emptyList()))
    }

    @Test
    fun `a small icon is converted to displayable PNG`() {
        val icon = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        icon.setRGB(10, 10, 0xff123456.toInt())
        val encoded = ByteArrayOutputStream().use { output ->
            ImageIO.write(icon, "tiff", output)
            output.toByteArray()
        }
        val png = assertNotNull(permissionGuideIconPng(encoded))
        val decoded = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(64, decoded.width)
        assertEquals(64, decoded.height)
        assertEquals(icon.getRGB(10, 10), decoded.getRGB(10, 10))
    }

    @Test
    fun `malformed and oversized encoded icons use the generic tile`() {
        assertNull(permissionGuideIconPng(byteArrayOf(1, 2, 3)))
        assertNull(permissionGuideIconPng(ByteArray(1_048_577)))
        val large = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(129, 129, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        assertNull(permissionGuideIconPng(large))
    }
}
