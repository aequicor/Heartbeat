package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Intrinsic page height and the CSS viewport width at which it was measured, independent of screen density. */
internal data class DesktopDocumentSize(val width: Int, val height: Int) {
    /** Scale CSS pixels to the caller's logical width; final layout still clamps to design-system bounds. */
    fun logicalHeight(viewportWidth: Float): Float? =
        viewportWidth.takeIf { it.isFinite() && it > 0 }?.let { height.toFloat() * it / width }
}

/**
 * Presentation-only reports of one native view. A new page invalidates old reports, close is terminal, and at most
 * one delivery is queued through [dispatch] regardless of the page's report rate. Neither reports nor dimensions
 * reach the owner's method bridge. [dispatch] schedules on the UI thread; offers and resets may come from CEF.
 */
internal class DesktopDocumentMeasurements(private val dispatch: (() -> Unit) -> Unit) {
    private val log = Log.tag("DesktopDocumentMeasurements")
    private val lock = Any()
    private val measured = MutableStateFlow<DesktopDocumentSize?>(null)
    private var generation = 0L
    private var pending: Report? = null
    private var isQueued = false
    private var isClosed = false

    val size: StateFlow<DesktopDocumentSize?> = measured.asStateFlow()

    /** Starts a fresh served page; measurements from previously served pages cannot change this view. */
    @HighFrequency
    fun beginPage(): Long = synchronized(lock) {
        generation++
        pending = null
        measured.value = null
        log.v { "Document measurements reset for a new page" }
        generation
    }

    /** A small numeric message, not arbitrary JSON; invalid, stale and post-close reports are ignored. */
    @HighFrequency
    fun offer(request: String) {
        val report = parse(request) ?: return
        val isDeliveryNeeded = synchronized(lock) {
            if (isClosed || report.generation != generation) return@synchronized false
            pending = report
            if (isQueued) {
                false
            } else {
                isQueued = true
                true
            }
        }
        if (isDeliveryNeeded) dispatch(::deliver)
    }

    /** Revokes queued reports and clears the retained measurement. */
    @HighFrequency
    fun close() = synchronized(lock) {
        isClosed = true
        pending = null
        measured.value = null
        log.v { "Document measurements revoked" }
    }

    @HighFrequency
    private fun deliver() = synchronized(lock) {
        val report = pending
        pending = null
        isQueued = false
        if (!isClosed && report != null && report.generation == generation) {
            if (measured.value != report.size) {
                measured.value = report.size
                log.v { "Document content measurement updated" }
            }
        }
    }

    private fun parse(request: String): Report? {
        val parts = request.takeIf { it.length <= MESSAGE_CHARS }?.split(':')
            ?.takeIf { it.size == FIELDS } ?: return null
        val page = parts.getOrNull(0)?.toLongOrNull()
        val width = parts.getOrNull(1).dimension()
        val height = parts.getOrNull(2).dimension()
        return if (page != null && width != null && height != null) {
            Report(page, DesktopDocumentSize(width, height))
        } else {
            null
        }
    }

    private fun String?.dimension(): Int? = this?.toIntOrNull()?.takeIf { it in 1..MAX_DIMENSION }

    private data class Report(val generation: Long, val size: DesktopDocumentSize)

    private companion object {
        const val MESSAGE_CHARS = 48
        const val FIELDS = 3

        // Protocol bound, not a viewport size: UI always clamps to the host's much smaller height tokens.
        const val MAX_DIMENSION = 100_000
    }
}
