package io.aequicor.heartbeat.feature.plantumlsupport.impl.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bounds of one drawing, against hostile or accidental huge sources: source size, wait time, scale, and the drawn
 * image (pixels and PNG bytes, in step with the design system's decoding limits). PlantUML allocates its canvas up to
 * [maxSide] squared before drawing, so the side bounds peak memory (4096² ARGB ≈ 64 MiB). Cache bounds keep recent
 * results.
 */
internal data class PlantUmlLimits(
    val maxSourceCharacters: Int = MAX_SOURCE_CHARACTERS,
    val timeout: Duration = TIMEOUT_SECONDS.seconds,
    val maxScale: Float = MAX_SCALE,
    val maxSide: Int = MAX_SIDE,
    val maxPixels: Long = MAX_PIXELS,
    val maxPngBytes: Int = MAX_PNG_BYTES,
    val cacheEntries: Int = CACHE_ENTRIES,
    val cacheBytes: Long = CACHE_BYTES,
)

private const val MEBI = 1024 * 1024
private const val MAX_SOURCE_CHARACTERS = 32 * 1024
private const val TIMEOUT_SECONDS = 20
private const val MAX_SCALE = 3f
private const val MAX_SIDE = 4096
private const val MAX_PIXELS = 16L * MEBI
private const val MAX_PNG_BYTES = 10 * MEBI
private const val CACHE_ENTRIES = 32
private const val CACHE_BYTES = 16L * MEBI
