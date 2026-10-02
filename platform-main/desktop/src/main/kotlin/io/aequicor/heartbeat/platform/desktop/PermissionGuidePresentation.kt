package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import java.util.concurrent.CancellationException

/**
 * Defers placement visibility changes during native suppression, so tracking System Settings cannot show the
 * guide halfway through capture or pointer input. The native lease preserves AWT visibility and restores without
 * activating the application; closing the guide invalidates its native presentation before pending leases return.
 * All methods run on the window's event thread.
 */
internal class PermissionGuidePresentation(
    private val native: ComputerUsePresentation,
    private val dispose: () -> Unit,
) : ComputerUsePresentation,
    AutoCloseable {
    private val log = Log.tag("PermissionGuidePresentation")
    private var isClosed = false
    private var suppressionCount = 0
    private var isPlaced = false
    var isShown by mutableStateOf(false)
        private set

    fun place(isVisible: Boolean) {
        isPlaced = isVisible
        if (!isClosed && suppressionCount == 0) show(isVisible)
    }

    override fun suppress(): AutoCloseable {
        check(!isClosed) { "The permission guide was disposed" }
        val lease = native.suppress()
        suppressionCount++
        var isReleased = false
        return AutoCloseable {
            if (!isReleased) {
                isReleased = true
                try {
                    lease.close()
                } finally {
                    suppressionCount--
                    if (!isClosed && suppressionCount == 0) show(isPlaced)
                }
            }
        }
    }

    private fun show(isVisible: Boolean) {
        if (isShown != isVisible) {
            log.d { "Permission guide visibility: $isShown -> $isVisible" }
            isShown = isVisible
        }
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            show(false)
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
