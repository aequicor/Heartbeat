package io.aequicor.heartbeat.feature.browser.impl.ui

import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import io.aequicor.heartbeat.feature.browser.impl.presentation.MacDocumentAppKitThread
import org.cef.browser.CefBrowserWindow
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Read-only AppKit evidence: unlike apply(), this probe cannot repair the defect it is measuring. */
internal class NativeDocumentClipSnapshot {
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

    fun read(component: JComponent): List<Boolean> {
        check(SwingUtilities.isEventDispatchThread())
        val owner = SwingUtilities.getWindowAncestor(component) ?: return emptyList()
        check(owner.isDisplayable)
        val handle = bridge.getWindowHandle(component)
        check(handle != 0L)
        val window = checkNotNull(retain.invokePointer(arrayOf(Pointer(handle))))
        var isTransferred = false
        return try {
            main.call {
                isTransferred = true
                try {
                    readViews(pointer(window, "contentView"))
                } finally {
                    release.invokeVoid(arrayOf(window))
                }
            }
        } finally {
            if (!isTransferred) release.invokeVoid(arrayOf(window))
        }
    }

    /** Bounded native-event observation, with no synthetic input or native mutation. */
    fun await(component: JComponent, count: Int, areAllClipped: Boolean? = null): List<Boolean> {
        val completed = CompletableFuture<List<Boolean>>()
        val timer = nativeEdt {
            Timer(20) {
                try {
                    val result = read(component)
                    if (result.size == count && (areAllClipped == null || result.all { it } == areAllClipped)) {
                        completed.complete(result)
                    }
                } catch (failure: Throwable) {
                    completed.completeExceptionally(failure)
                }
            }.apply { start() }
        }
        return try {
            completed.awaitNative()
        } finally {
            nativeEdt { timer.stop() }
        }
    }

    private fun readViews(root: Pointer?): List<Boolean> {
        val kind = getClass.invokePointer(arrayOf("CefBrowserContentView"))
        val pending = ArrayDeque<Pointer>()
        root?.let(pending::add)
        val result = mutableListOf<Boolean>()
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (objectClass.invokePointer(arrayOf(view)) == kind) {
                val value = message.invokeInt(arrayOf(view, selector.invokePointer(arrayOf("clipsToBounds"))))
                result += value and 0xff != 0
            }
            val children = pointer(view, "subviews") ?: continue
            val count = message.invokeLong(arrayOf(children, selector.invokePointer(arrayOf("count"))))
            for (index in 0 until count) {
                pending.add(checkNotNull(pointer(children, "objectAtIndex:", NativeLong(index))))
            }
        }
        return result
    }

    private fun pointer(receiver: Pointer, name: String, vararg args: Any): Pointer? =
        message.invokePointer(arrayOf(receiver, selector.invokePointer(arrayOf(name)), *args))
}
