package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserFaviconPolicy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    fun `ico icons are decoded and svg icons are refused`() {
        assertEquals(255, alpha(png(decodeBrowserFavicon(ico(testPng(16, 16)))), 16, 16))
        val svg = """
            <?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#36c"/></svg>
        """.trimIndent().encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(svg) }
    }

    @Test
    fun `oversized garbage and empty icons are refused`() {
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(testPng(1_025, 1_024)) }
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { decodeBrowserFavicon(ByteArray(MAX_FAVICON_BYTES + 1)) }
        assertFailsWith<Exception> { decodeBrowserFavicon("not an image".encodeToByteArray()) }
    }

    @Test
    fun `an unusable icon is not fetched again until browser data is cleared`() = runTest {
        val images = images()
        var calls = 0
        repeat(2) {
            images.load("https://example.com/favicon.ico") {
                calls++
                throw BrowserFaviconRejected("Site icon is not an image")
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
    fun `network failures and server errors are retried while unusable answers are not`() = runTest {
        val images = images()
        val calls = mutableMapOf<String, Int>()
        val failures = mapOf(
            "slow" to IllegalStateException("Source transfer timed out"),
            "busy" to DesktopBrowserSourceRejected("Source still unavailable", 503),
            "missing" to DesktopBrowserSourceRejected("Source still unavailable", 404),
            "huge" to DesktopBrowserSourceRejected("Site icon exceeds size limit"),
        )
        repeat(2) {
            failures.forEach { (name, failure) ->
                images.load("https://example.com/$name.ico") {
                    calls[name] = (calls[name] ?: 0) + 1
                    throw failure
                }
            }
            images.load("https://example.com/broken.ico") {
                calls["broken"] = (calls["broken"] ?: 0) + 1
                "not an image".encodeToByteArray()
            }
        }
        assertEquals(mapOf("slow" to 2, "busy" to 2, "missing" to 1, "huge" to 1, "broken" to 1), calls)
        assertTrue(images.images.value.isEmpty())
    }

    @Test
    fun `the least recently used icon is evicted first`() = runTest {
        val images = images()
        repeat(MAX_CACHED) { images.load("https://example.com/$it.png") { testPng(16, 16) } }
        images.load("https://example.com/0.png") { error("Cached icons are not fetched") }
        images.load("https://example.com/new.png") { testPng(16, 16) }
        assertTrue("https://example.com/0.png" in images.images.value)
        assertTrue("https://example.com/1.png" !in images.images.value)
        assertEquals(MAX_CACHED, images.images.value.size)
    }

    @Test
    fun `icon responses must be small images`() {
        val rejected = mutableListOf<String>()
        val body = DesktopBrowserIconBody { rejected += it }
        body.append(byteArrayOf(1, 2, 3), 3)
        assertEquals(3, body.result("https://example.com/i", "image/x-icon").size)
        assertFailsWith<BrowserFaviconRejected> { body.result("https://example.com/i", "text/html") }
        assertFailsWith<BrowserFaviconRejected> { body.result("https://example.com/i", "image/svg+xml") }
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

    private fun TestScope.images() = DesktopBrowserFaviconImages(
        BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        object : BrowserFaviconPolicy {
            override suspend fun isEnabled() = true

            override fun observe() = flowOf(true)
        },
    )

    private fun png(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun alpha(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) ushr 24

    private companion object {
        const val ICO_HEADER = 22
        const val MAX_CACHED = 128
    }
}
