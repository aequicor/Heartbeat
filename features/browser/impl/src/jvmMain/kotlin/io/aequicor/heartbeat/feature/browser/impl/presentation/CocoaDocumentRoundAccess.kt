package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import org.cef.browser.CefBrowserWindow
import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Selects the sole visible exact JCEF group in the attachment's current window, including its main window.
 * The modal host hides other native attachments before capture. Hidden groups and groups whose browser child
 * is hidden cannot become the target; multiple visible groups remain ambiguous and are never guessed by geometry.
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
        val scope = windowScope(component) ?: return DocumentRoundCapture.Absent
        val window = checkNotNull(retain.invokePointer(arrayOf(Pointer(scope.handle))))
        var isTransferred = false
        return try {
            main.call {
                isTransferred = true
                try {
                    captureWindow(window, scope.frame)
                } finally {
                    release.invokeVoid(arrayOf(window))
                }
            }
        } finally {
            // Before transfer, EDT still owns the displayable AWT peer and our extra retain cannot be its last.
            if (!isTransferred) release.invokeVoid(arrayOf(window))
        }
    }

    private fun windowScope(component: JComponent): WindowScope? {
        val root = SwingUtilities.getRootPane(component) ?: return null
        val owner = SwingUtilities.getWindowAncestor(component)
        val visible = component.visibleRect
        val isVisible = owner?.isDisplayable == true && component.isShowing && !visible.isEmpty
        return if (isVisible) {
            val origin = SwingUtilities.convertPoint(component, visible.location, root)
            val frame = Rectangle(origin.x, root.height - origin.y - visible.height, visible.width, visible.height)
            WindowScope(bridge.getWindowHandle(component), frame).takeIf { it.handle != 0L }
        } else {
            null
        }
    }

    private fun captureWindow(window: Pointer, expectedFrame: Rectangle): DocumentRoundCapture {
        val root = pointer(window, "contentView") ?: return DocumentRoundCapture.Absent
        val views = documentViews(root)
        return when (views.size) {
            0 -> DocumentRoundCapture.Absent

            1 -> if (frame(views.single()).matchesDocumentFrame(expectedFrame)) {
                DocumentRoundCapture.Found(captureTarget(views.single()))
            } else {
                DocumentRoundCapture.Absent
            }

            else -> DocumentRoundCapture.Ambiguous
        }
    }

    private fun documentViews(root: Pointer): List<Pointer> {
        val kind = getClass.invokePointer(arrayOf("CefBrowserContentView")) ?: return emptyList()
        return visibleNativeDocumentGroups(
            root,
            children = ::children,
            isDocumentGroup = { objectClass.invokePointer(arrayOf(it)) == kind },
            isHidden = { boolean(it, "isHiddenOrHasHiddenAncestor") },
        )
    }

    private fun children(view: Pointer): List<Pointer> {
        val children = pointer(view, "subviews") ?: return emptyList()
        val count = message.invokeLong(arguments(children, "count"))
        return (0 until count).map { index ->
            checkNotNull(pointer(children, "objectAtIndex:", NativeLong(index)))
        }
    }

    /** Public KVC boxes CGRect into NSValue without architecture-specific objc_msgSend_stret conventions. */
    private fun frame(view: Pointer): Rectangle2D.Double {
        val strings = checkNotNull(getClass.invokePointer(arrayOf("NSString")))
        val key = checkNotNull(pointer(strings, "stringWithUTF8String:", "frame"))
        val boxed = checkNotNull(pointer(view, "valueForKey:", key))
        return Memory(FRAME_FIELD_COUNT.toLong() * Double.SIZE_BYTES).use { value ->
            message.invokeVoid(arguments(boxed, "getValue:size:", value, NativeLong(value.size())))
            val fields = value.getDoubleArray(0, FRAME_FIELD_COUNT)
            Rectangle2D.Double(fields[FRAME_X], fields[FRAME_Y], fields[FRAME_WIDTH], fields[FRAME_HEIGHT])
        }
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
        const val FRAME_FIELD_COUNT = 4
        const val FRAME_X = 0
        const val FRAME_Y = 1
        const val FRAME_WIDTH = 2
        const val FRAME_HEIGHT = 3
    }

    private data class WindowScope(val handle: Long, val frame: Rectangle)
}
