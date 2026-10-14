package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import org.cef.browser.CefBrowserWindow
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Selects one exact JCEF group in an owned modal window, never in the primary application window.
 * The selected group and its layer are retained until the attachment restores their original properties.
 * All view/layer access runs on AppKit; the public JCEF window bridge is called only on EDT.
 */
internal class CocoaDocumentRoundAccess : DocumentRoundAccess {
    private val objc = NativeLibrary.getInstance("objc")
    private val message = objc.getFunction("objc_msgSend")
    private val selector = objc.getFunction("sel_registerName")
    private val getClass = objc.getFunction("objc_getClass")
    private val objectClass = objc.getFunction("object_getClass")
    private val retain = objc.getFunction("objc_retain")
    private val release = objc.getFunction("objc_release")
    private val main = MacDocumentAppKitThread()
    private val bridge = Class.forName("org.cef.browser.mac.CefBrowserWindowMac")
        .getConstructor().newInstance() as CefBrowserWindow

    override fun capture(component: JComponent): DocumentRoundCapture {
        check(SwingUtilities.isEventDispatchThread())
        val handle = modalHandle(component)
        if (handle == 0L) return DocumentRoundCapture.Absent
        val window = checkNotNull(retain.invokePointer(arrayOf(Pointer(handle))))
        var isTransferred = false
        return try {
            main.call {
                isTransferred = true
                try {
                    captureWindow(window)
                } finally {
                    release.invokeVoid(arrayOf(window))
                }
            }
        } finally {
            // Before transfer, EDT still owns the displayable AWT peer and our extra retain cannot be its last.
            if (!isTransferred) release.invokeVoid(arrayOf(window))
        }
    }

    private fun modalHandle(component: JComponent): Long {
        val owner = SwingUtilities.getWindowAncestor(component) ?: return 0
        return if (owner.owner != null && owner.isDisplayable && component.isShowing) {
            bridge.getWindowHandle(component)
        } else {
            0
        }
    }

    private fun captureWindow(window: Pointer): DocumentRoundCapture {
        val root = pointer(window, "contentView") ?: return DocumentRoundCapture.Absent
        val views = documentViews(root)
        return when (views.size) {
            0 -> DocumentRoundCapture.Absent
            1 -> DocumentRoundCapture.Found(captureTarget(views.single()))
            else -> DocumentRoundCapture.Ambiguous
        }
    }

    private fun documentViews(root: Pointer): List<Pointer> {
        val kind = getClass.invokePointer(arrayOf("CefBrowserContentView")) ?: return emptyList()
        val pending = ArrayDeque<Pointer>()
        val matches = mutableListOf<Pointer>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (objectClass.invokePointer(arrayOf(view)) == kind) matches.add(view)
            val children = pointer(view, "subviews") ?: continue
            val count = message.invokeLong(arguments(children, "count"))
            for (index in 0 until count) {
                pending.add(checkNotNull(pointer(children, "objectAtIndex:", NativeLong(index))))
            }
        }
        return matches
    }

    private fun captureTarget(view: Pointer): DocumentRoundTarget {
        val layer = checkNotNull(pointer(view, "layer")) { "The native document layer is unavailable" }
        val radius = message.invokeDouble(arguments(layer, "cornerRadius"))
        val isMasked = boolean(layer, "masksToBounds")
        retain.invokePointer(arrayOf(view))
        var isLayerRetained = false
        try {
            checkNotNull(retain.invokePointer(arrayOf(layer)))
            isLayerRetained = true
        } finally {
            if (!isLayerRetained) release.invokeVoid(arrayOf(view))
        }
        return Target(view, layer, radius, isMasked)
    }

    private fun pointer(receiver: Pointer, name: String, vararg args: Any): Pointer? =
        message.invokePointer(arguments(receiver, name, *args))

    private fun boolean(receiver: Pointer, name: String): Boolean =
        message.invokeInt(arguments(receiver, name)) and BOOLEAN_MASK != 0

    private fun arguments(receiver: Pointer, name: String, vararg args: Any): Array<Any?> =
        arrayOf(receiver, selector.invokePointer(arrayOf(name)), *args)

    private inner class Target(
        private val view: Pointer,
        private val layer: Pointer,
        private val previousRadius: Double,
        private val isPreviouslyMasked: Boolean,
    ) : DocumentRoundTarget {
        private var isClosed = false

        override fun round(radius: Double) {
            main.call {
                check(!isClosed)
                set(radius, true)
                check(
                    message.invokeDouble(arguments(layer, "cornerRadius")) == radius && boolean(layer, "masksToBounds"),
                ) {
                    "The native document layer refused rounded clipping"
                }
            }
        }

        override fun close() {
            main.call {
                if (!isClosed) {
                    isClosed = true
                    try {
                        set(previousRadius, isPreviouslyMasked)
                    } finally {
                        release.invokeVoid(arrayOf(layer))
                        release.invokeVoid(arrayOf(view))
                    }
                }
            }
        }

        private fun set(radius: Double, isMasked: Boolean) {
            message.invokeVoid(arguments(layer, "setCornerRadius:", radius))
            message.invokeVoid(arguments(layer, "setMasksToBounds:", if (isMasked) 1.toByte() else 0.toByte()))
        }
    }

    private companion object {
        const val BOOLEAN_MASK = 0xff
    }
}
