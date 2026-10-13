package io.aequicor.heartbeat.feature.aistudio.impl.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal fun attachmentImageFixture(): ByteArray {
    val image = BufferedImage(128, 80, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(59, 102, 170)
        graphics.fillRect(0, 0, 128, 80)
        graphics.color = Color(247, 200, 74)
        graphics.fillOval(42, 12, 40, 32)
        graphics.color = Color(30, 140, 99)
        graphics.fillRect(0, 48, 128, 32)
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}
