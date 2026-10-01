package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Least-recently-used cache of the decoded master frames of one capture session.
 *
 * A crop must be cut out of exactly the frame the caller saw, so masters stay addressable after the screen
 * changed. Decoded pixels are evicted by total size and re-decoded from the stored artifact on the next crop;
 * when the artifact itself is gone the crop fails with `CaptureExpired` instead of silently capturing again.
 */
internal class MasterFrameCache(
    private val encoder: FrameEncoder,
    private val store: FrameStore,
    private val maxBytes: Long,
) {
    private val log = Log.tag("MasterFrameCache")
    private val mutex = Mutex()
    private val entries = LinkedHashMap<CaptureId, Entry>()

    /** Remembers [reference] and its decoded [pixels], evicting the oldest buffers over the size limit. */
    suspend fun put(reference: CaptureRef, pixels: PixelGrid) {
        mutex.withLock {
            entries.remove(reference.id)
            entries[reference.id] = Entry(reference, pixels)
            evict()
        }
        log.d { "master cached id=${reference.id} size=${pixels.widthPx}x${pixels.heightPx}" }
    }

    /** The reference of a cached master frame, or `null` when this session never produced it. */
    suspend fun reference(id: CaptureId): CaptureRef? = mutex.withLock {
        entries[id]?.let { entry ->
            entries.remove(id)
            entries[id] = entry
            entry.reference
        }
    }

    /** The decoded pixels of a master frame, re-decoding the stored artifact when its buffer was evicted. */
    suspend fun pixels(id: CaptureId): PixelGrid? = mutex.withLock {
        val entry = entries[id] ?: return@withLock null
        entries.remove(id)
        entries[id] = entry
        val cached = entry.pixels
        if (cached != null) {
            cached
        } else {
            reload(entry)
        }
    }

    /** Forgets every frame of [session] and deletes its stored artifacts. */
    suspend fun forgetSession(session: CaptureSessionId) {
        val removed = mutex.withLock {
            val ids = entries.filterValues { it.reference.session == session }.keys
            ids.forEach { entries.remove(it) }
            ids.size
        }
        try {
            store.delete(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "master purge failed session=$session" }
        }
        log.i { "masters forgotten session=$session count=$removed" }
    }

    /** Number of remembered master frames; diagnostics only. */
    suspend fun size(): Int = mutex.withLock { entries.size }

    /** Drops the oldest decoded buffers while the resident size is over the limit; references stay. */
    private fun evict() {
        var resident = entries.values.sumOf { it.bytes }
        val iterator = entries.entries.iterator()
        while (resident > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            val pixels = entry.value.pixels ?: continue
            entry.value.pixels = null
            resident -= pixels.argb.size.toLong() * BYTES_PER_PIXEL
            log.d { "master buffer evicted id=${entry.key}" }
        }
    }

    private suspend fun reload(entry: Entry): PixelGrid? {
        val content = try {
            store.read(entry.reference.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "master reload failed id=${entry.reference.id}" }
            null
        } ?: return null
        val decoded = try {
            encoder.decode(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "master decode failed id=${entry.reference.id}" }
            null
        } ?: return null
        entry.pixels = decoded
        evict()
        return decoded
    }

    private class Entry(val reference: CaptureRef, var pixels: PixelGrid?) {
        val bytes: Long get() = pixels?.argb?.size?.toLong()?.times(BYTES_PER_PIXEL) ?: 0L
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4L
    }
}
