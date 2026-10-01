package io.aequicor.heartbeat.feature.researchchat.impl.domain

/** Pure bounded image processing supplied by the rendering adapter; retains no file or mutable cache. */
internal fun interface ResearchThumbnailEncoder {
    fun encode(bytes: ByteArray): ByteArray
}
