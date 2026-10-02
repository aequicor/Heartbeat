package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import java.util.concurrent.CancellationException

/**
 * Owns actual AWT visibility. Compose's Window stays declaratively hidden because its deferred setVisible(true)
 * can otherwise run after native suppression took a snapshot of an as-yet hidden panel. Every queued update
 * checks the current placement, suppression and lifetime on the event thread immediately before applying it.
 *
 * Native suppression preserves the logical AWT visibility of an already visible panel. Nested leases keep the
 * visibility gate closed until the last restore finishes; closing permanently rejects all queued updates.
 */
internal class PermissionGuidePresentation(
    private val native: ComputerUsePresentation,
    private val dispose: () -> Unit,
    private val setVisible: (Boolean) -> Unit,
    private val enqueue: (() -> Unit) -> Unit,
) : ComputerUsePresentation,
    AutoCloseable {
    private val log = Log.tag("PermissionGuidePresentation")
    private var isClosed = false
    private var suppressionCount = 0
    private var isPlaced = false

    fun place(isVisible: Boolean) {
        if (isClosed) return
        if (isPlaced != isVisible) log.d { "Permission guide placement: $isPlaced -> $isVisible" }
        isPlaced = isVisible
        enqueueVisibility()
    }

    override fun suppress(): AutoCloseable {
        check(!isClosed) { "The permission guide was disposed" }
        // Close the gate before native work: its AppKit round trip must not admit a queued show either.
        suppressionCount++
        var isAcquired = false
        val lease = try {
            native.suppress().also { isAcquired = true }
        } finally {
            if (!isAcquired) {
                suppressionCount--
                enqueueVisibility()
            }
        }
        var isReleased = false
        return AutoCloseable {
            if (!isReleased) {
                isReleased = true
                try {
                    lease.close()
                } finally {
                    suppressionCount--
                    if (suppressionCount == 0) enqueueVisibility()
                }
            }
        }
    }

    private fun enqueueVisibility() {
        if (isClosed) return
        enqueue {
            if (!isClosed && suppressionCount == 0) {
                permissionGuideOrNull("update panel visibility") { setVisible(isPlaced) }
            }
        }
    }

    override fun close() {
        if (!isClosed) {
            log.d { "Permission guide disposed; pending visibility updates rejected" }
            isClosed = true
            dispose()
        }
    }
}

/** Native setup, metadata and tracking are optional guidance; failures must not escape the application scope. */
internal inline fun <T> permissionGuideOrNull(operation: String, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.tag("PermissionGuideWindow").w(e) { "Permission guide could not $operation" }
    null
} catch (e: LinkageError) {
    Log.tag("PermissionGuideWindow").w(e) { "Permission guide native support could not $operation" }
    null
}
