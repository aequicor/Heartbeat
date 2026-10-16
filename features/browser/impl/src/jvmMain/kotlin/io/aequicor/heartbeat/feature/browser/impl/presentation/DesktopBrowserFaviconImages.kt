package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BROWSER_FAVICON_PIXELS
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserFaviconPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Desktop tabs look icons up only while the feature flag is on, and load them through their own profile. */
internal interface DesktopBrowserFavicons {
    /** Decoded icons by address. */
    val images: StateFlow<Map<String, BrowserFaviconImage>>

    suspend fun isEnabled(): Boolean

    /** Whether the feature flag is on, emitting each change. */
    fun observeEnabled(): Flow<Boolean>

    /** Fetches and decodes [url] through [fetch] unless it is already known; the caller's cancellation stops it. */
    suspend fun load(url: String, fetch: suspend (String) -> ByteArray)

    /** Clearing browser data forgets which sites were shown. */
    fun clear()
}

/**
 * Site icons of the profile's desktop tabs, decoded once per address and kept in memory only (the [MAX_IMAGES] most
 * recently used). An address whose response is not a usable icon ([BrowserFaviconRejected], an HTTP error such as 404,
 * an oversized body) is not fetched again until browser data is cleared; a network failure, timeout, server error or
 * refused destination is retried with the next page load. Main-confined.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<DesktopBrowserFavicons>())
@Inject
internal class DesktopBrowserFaviconImages(
    private val dispatchers: DispatcherProvider,
    private val policy: BrowserFaviconPolicy,
) : DesktopBrowserFavicons {
    private val log = Log.tag("BrowserFavicons")
    private val cache = MutableStateFlow<Map<String, BrowserFaviconImage>>(emptyMap())
    private val failed = LinkedHashSet<String>()

    /** Cached addresses, least recently used first; a map with the same entries would not re-emit a new order. */
    private val recent = LinkedHashSet<String>()

    override val images: StateFlow<Map<String, BrowserFaviconImage>> = cache.asStateFlow()

    override suspend fun isEnabled(): Boolean = policy.isEnabled()

    override fun observeEnabled(): Flow<Boolean> = policy.observe()

    override suspend fun load(url: String, fetch: suspend (String) -> ByteArray) {
        if (url in cache.value) {
            recent.remove(url)
            recent += url
            return
        }
        if (url in failed) return
        try {
            val bytes = fetch(url)
            val image = BrowserFaviconImage(decode(bytes))
            recent += url
            val evicted = recent.take((recent.size - MAX_IMAGES).coerceAtLeast(0)).toSet()
            recent -= evicted
            cache.value = cache.value - evicted + (url to image)
            log.v { "Site icon cached: total=${cache.value.size}" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isPermanentIconFailure()) {
                failed += url
                if (failed.size > MAX_FAILURES) failed.remove(failed.first())
                log.w(error.redactedFailure()) { "Site icon is not usable" }
            } else {
                log.w(error.redactedFailure()) { "Site icon is unavailable" }
            }
        }
    }

    /** Bytes the decoder refuses are as unusable as a non-image response. */
    private suspend fun decode(bytes: ByteArray): ByteArray = withContext(dispatchers.default) {
        try {
            decodeBrowserFavicon(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw BrowserFaviconRejected("Site icon cannot be decoded", error)
        }
    }

    override fun clear() {
        cache.value = emptyMap()
        recent.clear()
        failed.clear()
        log.i { "Site icons cleared" }
    }

    private companion object {
        const val MAX_IMAGES = 128
        const val MAX_FAILURES = 256
    }
}

private fun Exception.isPermanentIconFailure(): Boolean =
    this is BrowserFaviconRejected || (this is DesktopBrowserSourceRejected && !isRetryable)

/** A response that is not a usable site icon; retrying the same address would get the same answer. */
internal class BrowserFaviconRejected(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Renders a raster site icon (PNG, ICO, GIF, WebP, JPEG or BMP) into a [BROWSER_FAVICON_PIXELS] square PNG, keeping
 * its aspect ratio. Sources larger than [MAX_FAVICON_SOURCE_PIXELS] are refused before decoding. SVG is refused:
 * Skia's renderer cannot be interrupted or bounded, and a small hostile document (nested `<use>`) renders for minutes.
 */
internal fun decodeBrowserFavicon(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= MAX_FAVICON_BYTES) { "Site icon size is invalid" }
    require(!isSvg(bytes)) { "SVG site icons are not supported" }
    return Data.makeFromBytes(bytes).use(::renderRaster)
}

private fun renderRaster(data: Data): ByteArray = Codec.makeFromData(data).use { codec ->
    val width = codec.imageInfo.width
    val height = codec.imageInfo.height
    require(width > 0 && height > 0 && width.toLong() * height <= MAX_FAVICON_SOURCE_PIXELS) {
        "Site icon exceeds pixel limit"
    }
    Bitmap().use { bitmap ->
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height))
        codec.readPixels(bitmap)
        Image.makeFromBitmap(bitmap).use { source ->
            val scale = BROWSER_FAVICON_PIXELS.toFloat() / maxOf(width, height)
            val target = Rect.makeXYWH(
                (BROWSER_FAVICON_PIXELS - width * scale) / 2,
                (BROWSER_FAVICON_PIXELS - height * scale) / 2,
                width * scale,
                height * scale,
            )
            render { canvas ->
                canvas.drawImageRect(
                    source,
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    target,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    null,
                    true,
                )
            }
        }
    }
}

private fun render(draw: (Canvas) -> Unit): ByteArray =
    Surface.makeRasterN32Premul(BROWSER_FAVICON_PIXELS, BROWSER_FAVICON_PIXELS).use { surface ->
        draw(surface.canvas)
        surface.makeImageSnapshot().use { snapshot ->
            checkNotNull(snapshot.encodeToData()) { "Site icon could not be encoded" }.use { it.bytes }
        }
    }

/** SVG is text that starts with markup; every raster format the codecs accept starts with a binary signature. */
private fun isSvg(bytes: ByteArray): Boolean {
    val head = bytes.copyOf(minOf(bytes.size, SVG_SNIFF_BYTES)).decodeToString()
        .trimStart(BYTE_ORDER_MARK, ' ', '\t', '\r', '\n')
    return head.startsWith("<") && "<svg" in head
}

private const val SVG_SNIFF_BYTES = 1_024
private const val BYTE_ORDER_MARK = '﻿'

/** Raster icons are small; anything larger would only cost memory to scale down to a tab icon. */
internal const val MAX_FAVICON_SOURCE_PIXELS = 1_024L * 1_024L
