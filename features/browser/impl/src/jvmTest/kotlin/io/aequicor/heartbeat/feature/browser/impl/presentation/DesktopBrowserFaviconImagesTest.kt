package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserFaviconPolicy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopBrowserFaviconImagesTest {
    @Test
    fun `raster icons scale into a square tab bitmap keeping their aspect`() {
        val icon = png(decodeBrowserFavicon(testPng(64, 32)))
        assertEquals(32, icon.width)
        assertEquals(32, icon.height)
        assertEquals(0, alpha(icon, 16, 2))
        assertEquals(255, alpha(icon, 16, 16))
    }

    @Test
    fun `ico and svg icons are decoded`() {
        assertEquals(255, alpha(png(decodeBrowserFavicon(ico(testPng(16, 16)))), 16, 16))
        val svg = """
            <?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#36c"/></svg>
        """.trimIndent().encodeToByteArray()
        assertEquals(255, alpha(png(decodeBrowserFavicon(svg)), 16, 16))
    }

    @Test
    fun `oversized garbage and empty icons are refused`() {
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(testPng(1_025, 1_024)) }
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(ByteArray(MAX_FAVICON_BYTES + 1)) }
        assertFailsWith<Exception> { decodeBrowserFavicon("not an image".encodeToByteArray()) }
    }

    @Test
    fun `a failed icon is not fetched again until browser data is cleared`() = runTest {
        val images = DesktopBrowserFaviconImages(
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
            object : BrowserFaviconPolicy {
                override suspend fun isEnabled() = true
            },
        )
        var calls = 0
        repeat(2) {
            images.load("https://example.com/favicon.ico") {
                calls++
                error("Not found")
            }
        }
        assertEquals(1, calls)
        assertTrue(images.images.value.isEmpty())
        images.clear()
        images.load("https://example.com/favicon.ico") {
            calls++
            testPng(16, 16)
        }
        assertEquals(2, calls)
        assertEquals(setOf("https://example.com/favicon.ico"), images.images.value.keys)
    }

    @Test
    fun `icon responses must be small images`() {
        val rejected = mutableListOf<String>()
        val body = DesktopBrowserIconBody { rejected += it }
        body.append(byteArrayOf(1, 2, 3), 3)
        assertEquals(3, body.result("https://example.com/i", "image/x-icon").size)
        assertFailsWith<IllegalStateException> { body.result("https://example.com/i", "text/html") }
        assertFailsWith<IllegalStateException> { DesktopBrowserIconBody {}.result("https://example.com/i", "") }
        body.progress(0, MAX_FAVICON_BYTES + 1)
        assertEquals(listOf("Site icon exceeds size limit"), rejected)
        assertFailsWith<IllegalStateException> { body.result("https://example.com/i", "image/png") }
    }

    /** An ICO directory with one embedded PNG image, as modern sites serve favicon.ico. */
    private fun ico(png: ByteArray): ByteArray =
        ByteBuffer.allocate(ICO_HEADER + png.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(1).putShort(1)
            .put(16).put(16).put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(ICO_HEADER)
            .put(png).array()

    private fun png(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun alpha(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) ushr 24

    private companion object {
        const val ICO_HEADER = 22
    }
}
