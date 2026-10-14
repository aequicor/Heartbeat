package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.coroutines.CancellationException
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * A corner mask belongs to the current edge-to-edge modal attachment, never to its window's other browsers.
 * Capture succeeds only for one exact native document group in that owned window. Detach restores and releases
 * that same retained target, without discovering anything in the replacement Start window. EDT only.
 */
internal class MacDocumentNativeRounding(access: DocumentRoundAccess? = null) : AutoCloseable {
    private val log = Log.tag("DocumentRounding")
    private val nativeAccess by lazy { access ?: CocoaDocumentRoundAccess() }
    private var radius = 0.0
    private var target: DocumentRoundTarget? = null
    private var isClosed = false
    private var isFailed = false
    private var isAmbiguityLogged = false

    fun update(value: Double) {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed || radius == value) return
        require(value.isFinite() && value >= 0)
        radius = value
        isFailed = false
        isAmbiguityLogged = false
        guarded(::clear)
    }

    /** Called by the existing bounded native-attachment retry burst, including its trailing paint retry. */
    fun apply(component: JComponent) {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed || isFailed || radius == 0.0) return
        val isApplied = guarded {
            if (target == null) {
                when (val captured = nativeAccess.capture(component)) {
                    DocumentRoundCapture.Absent -> Unit

                    DocumentRoundCapture.Ambiguous -> if (!isAmbiguityLogged) {
                        isAmbiguityLogged = true
                        log.w { "Native modal rounding skipped because its document target is ambiguous" }
                    }

                    is DocumentRoundCapture.Found -> target = captured.target
                }
            }
            target?.round(radius)
        }
        if (!isApplied) guarded(::clear)
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        isClosed = true
        radius = 0.0
        guarded(::clear)
    }

    private fun clear() {
        val previous = target
        target = null
        previous?.close()
    }

    private fun guarded(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        fail(error)
    } catch (error: LinkageError) {
        fail(error)
    }

    private fun fail(error: Throwable): Boolean {
        isFailed = true
        log.w(error.redactedFailure()) { "Native modal rounding is unavailable" }
        return false
    }
}

/** A capture is a retained exact native view, or no permission to choose among the window's views. */
internal sealed interface DocumentRoundCapture {
    data object Absent : DocumentRoundCapture
    data object Ambiguous : DocumentRoundCapture
    data class Found(val target: DocumentRoundTarget) : DocumentRoundCapture
}

internal fun interface DocumentRoundAccess {
    fun capture(component: JComponent): DocumentRoundCapture
}

/** Restore the prior layer properties and release the retained view exactly once on close. */
internal interface DocumentRoundTarget : AutoCloseable {
    fun round(radius: Double)
}
