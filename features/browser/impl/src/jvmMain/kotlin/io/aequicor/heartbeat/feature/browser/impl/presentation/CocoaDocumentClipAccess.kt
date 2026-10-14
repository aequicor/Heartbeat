package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import org.cef.browser.CefBrowserWindow
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Public JCEF window bridge plus AppKit properties; no private Java members or global window discovery. */
internal class CocoaDocumentClipAccess : MacDocumentClipAccess {
    private val objc = NativeLibrary.getInstance("objc")
    private val message = objc.getFunction("objc_msgSend")
    private val selector = objc.getFunction("sel_registerName")
    private val getClass = objc.getFunction("objc_getClass")
    private val objectClass = objc.getFunction("object_getClass")
    private val retain = objc.getFunction("objc_retain")
    private val release = objc.getFunction("objc_release")
    private val main = MacDocumentAppKitThread()

    // This public platform class lives in the checksum-pinned macOS native jar, just as in JCEF's own loader.
    private val bridge = Class.forName("org.cef.browser.mac.CefBrowserWindowMac")
        .getConstructor().newInstance() as CefBrowserWindow

    override fun apply(component: JComponent): Int {
        check(SwingUtilities.isEventDispatchThread())
        val handle = visibleWindowHandle(component)
        if (handle == 0L) return 0
        val window = checkNotNull(retain.invokePointer(arrayOf(Pointer(handle))))
        var isTransferred = false
        return try {
            main.call {
                isTransferred = true
                try {
                    val root = pointer(window, "contentView")
                    val kind = getClass.invokePointer(arrayOf("CefBrowserContentView"))
                    if (root == null || kind == null) {
                        0
                    } else {
                        clipDocumentViewTree(Pointer.nativeValue(root), NativeViewTree(kind))
                    }
                } finally {
                    release.invokeVoid(arrayOf(window))
                }
            }
        } finally {
            // Scheduling can fail before transfer. EDT still owns the displayable AWT peer, so dropping only
            // our additional retain here cannot destroy its window. Normal release always runs on AppKit.
            if (!isTransferred) release.invokeVoid(arrayOf(window))
        }
    }

    private fun visibleWindowHandle(component: JComponent): Long {
        val owner = SwingUtilities.getWindowAncestor(component) ?: return 0
        return if (owner.isDisplayable && component.isShowing) bridge.getWindowHandle(component) else 0
    }

    private fun pointer(receiver: Pointer, name: String, vararg args: Any): Pointer? =
        message.invokePointer(arrayOf(receiver, selector.invokePointer(arrayOf(name)), *args))

    private inner class NativeViewTree(private val kind: Pointer) : DocumentNativeViewTree {
        override fun children(view: Long): List<Long> {
            val array = pointer(Pointer(view), "subviews") ?: return emptyList()
            val count = message.invokeLong(arrayOf(array, selector.invokePointer(arrayOf("count"))))
            return (0 until count).map { index ->
                Pointer.nativeValue(checkNotNull(pointer(array, "objectAtIndex:", NativeLong(index))))
            }
        }

        override fun isDocumentContent(view: Long): Boolean = objectClass.invokePointer(arrayOf(Pointer(view))) == kind

        override fun enableClipping(view: Long) {
            val receiver = Pointer(view)
            message.invokeVoid(arrayOf(receiver, selector.invokePointer(arrayOf("setClipsToBounds:")), 1.toByte()))
            val clipped = message.invokeInt(arrayOf(receiver, selector.invokePointer(arrayOf("clipsToBounds"))))
            check(clipped and BOOLEAN_MASK != 0) { "The native document view refused clipping" }
        }
    }

    private companion object {
        const val BOOLEAN_MASK = 0xff
    }
}

/** Native views are borrowed only for the duration of one AppKit-thread traversal of an owned window. */
internal interface DocumentNativeViewTree {
    fun children(view: Long): List<Long>
    fun isDocumentContent(view: Long): Boolean
    fun enableClipping(view: Long)
}

/** Matches only the pinned runtime's exact content-view class, without touching unrelated sibling views. */
internal fun clipDocumentViewTree(root: Long, tree: DocumentNativeViewTree): Int {
    val pending = ArrayDeque<Long>()
    pending.add(root)
    var count = 0
    while (pending.isNotEmpty()) {
        val view = pending.removeFirst()
        if (tree.isDocumentContent(view)) {
            tree.enableClipping(view)
            count++
        }
        pending.addAll(tree.children(view))
    }
    return count
}
