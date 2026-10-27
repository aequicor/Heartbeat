package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Drags a file out of a native window the way Finder does: AppKit itself fills the drag pasteboard, including
 * `public.file-url`, which current System Settings lists require (AWT's own drag offers only the legacy
 * `NSFilenamesPboardType`, so such a list rejects the drop and the icon slides back).
 */
internal class MacFileDrag(private val cocoa: MacWindowAccess) {
    private val objc = NativeLibrary.getInstance("objc")
    private val message = objc.getFunction("objc_msgSend")
    private val selector = objc.getFunction("sel_registerName")
    private val lookUpClass = objc.getFunction("objc_getClass")

    /**
     * Drags [path] from [window] until the drop; the gesture is the mouse press already in progress, so the drag
     * image starts at the pointer. Blocks the AppKit main thread meanwhile, so call it off the AWT event thread.
     */
    fun drag(window: Pointer, path: String) {
        cocoa.onMainThread {
            val view = cocoa.pointer(window, "contentView")
            val at = message.invoke(
                NsPoint::class.java,
                arrayOf(window, select("mouseLocationOutsideOfEventStream")),
            ) as NsPoint
            val info = cocoa.pointer(objcClass("NSProcessInfo"), "processInfo")
            val uptime = message.invokeDouble(arrayOf(info, select("systemUptime")))
            val event = cocoa.pointer(
                objcClass("NSEvent"),
                "mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:clickCount:" +
                    "pressure:",
                NativeLong(LEFT_MOUSE_DRAGGED),
                NsPoint(at.x, at.y),
                NativeLong(0),
                uptime,
                NativeLong(cocoa.number(window, "windowNumber")),
                null,
                NativeLong(0),
                NativeLong(1),
                1f,
            )
            val rect = NsRect(at.x - ICON_SIDE / 2, at.y - ICON_SIDE / 2, ICON_SIDE, ICON_SIDE)
            val nsPath = cocoa.pointer(objcClass("NSString"), "stringWithUTF8String:", path)
            message.invokeInt(arrayOf(view, select("dragFile:fromRect:slideBack:event:"), nsPath, rect, 1, event))
        }
    }

    private fun select(name: String): Pointer = selector.invokePointer(arrayOf(name))

    private fun objcClass(name: String): Pointer =
        checkNotNull(lookUpClass.invokePointer(arrayOf(name))) { "Cocoa class $name is missing" }

    @Structure.FieldOrder("x", "y")
    class NsPoint(
        @JvmField var x: Double = 0.0,
        @JvmField var y: Double = 0.0,
    ) : Structure(),
        Structure.ByValue

    @Structure.FieldOrder("x", "y", "width", "height")
    class NsRect(
        @JvmField var x: Double = 0.0,
        @JvmField var y: Double = 0.0,
        @JvmField var width: Double = 0.0,
        @JvmField var height: Double = 0.0,
    ) : Structure(),
        Structure.ByValue

    private companion object {
        // NSEventTypeLeftMouseDragged and the side of the drag image rect
        const val LEFT_MOUSE_DRAGGED = 6L
        const val ICON_SIDE = 32.0
    }
}
