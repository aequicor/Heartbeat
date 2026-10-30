package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMException
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.aequicor.heartbeat.core.logging.Log
import javax.swing.JFrame

/** Registers an already visible HWND without hide/show, which would expose the desktop during handoff. */
internal object WindowsTaskbar {
    fun install(window: JFrame) {
        if (window.rootPane.getClientProperty(ENTRY_KEY) != null) return
        var registeredHandle: HWND? = null
        window.rootPane.putClientProperty(
            ENTRY_KEY,
            WindowsTaskbarEntry(window, { window.rootPane.putClientProperty(ENTRY_KEY, null) }) {
                if (it) {
                    val handle = HWND(com.sun.jna.Native.getWindowPointer(window))
                    restoreSwitcherStyle(handle)
                    update(handle, true)
                    registeredHandle = handle
                } else {
                    registeredHandle?.let { handle -> update(handle, false) }
                    registeredHandle = null
                }
            },
        )
    }

    private fun restoreSwitcherStyle(handle: HWND) {
        val user = User32.INSTANCE
        val kernel = Kernel32.INSTANCE
        kernel.SetLastError(0)
        val style = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
        check(style != 0 || kernel.GetLastError() == 0) { "Cannot read the restored Studio style" }
        val restored = (style and TOOLWINDOW.inv()) or APPWINDOW
        if (restored == style) return
        kernel.SetLastError(0)
        val previous = user.SetWindowLong(handle, WinUser.GWL_EXSTYLE, restored)
        check(previous != 0 || kernel.GetLastError() == 0) { "Cannot restore the Studio switcher style" }
    }

    private fun update(window: HWND, visible: Boolean) {
        try {
            register(window, visible)
            Log.tag("WindowsTaskbar").i { "Taskbar entry updated: visible=$visible" }
        } catch (error: COMException) {
            Log.tag("WindowsTaskbar").w(error) { "Cannot update the Studio taskbar entry: visible=$visible" }
        }
    }

    private fun register(window: HWND, visible: Boolean) {
        val ole = Ole32.INSTANCE
        val initialized = ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        if (initialized.toInt() != WinError.RPC_E_CHANGED_MODE) COMUtils.checkRC(initialized)
        try {
            val result = PointerByReference()
            COMUtils.checkRC(
                ole.CoCreateInstance(
                    Guid.CLSID("{56FDF344-FD6D-11D0-958A-006097C9A090}"),
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    Guid.IID("{56FDF342-FD6D-11D0-958A-006097C9A090}"),
                    result,
                ),
            )
            val taskbar = TaskbarList(checkNotNull(result.value))
            try {
                taskbar.initialize()
                taskbar.update(window, visible)
            } finally {
                taskbar.Release()
            }
        } finally {
            if (initialized.toInt() >= 0) ole.CoUninitialize()
        }
    }

    private class TaskbarList(pointer: Pointer) : Unknown(pointer) {
        fun initialize() = COMUtils.checkRC(
            _invokeNativeObject(INITIALIZE, arrayOf(pointer), HRESULT::class.java) as HRESULT,
        )

        fun update(window: HWND, visible: Boolean) = COMUtils.checkRC(
            _invokeNativeObject(
                if (visible) ADD_TAB else DELETE_TAB,
                arrayOf(pointer, window),
                HRESULT::class.java,
            ) as HRESULT,
        )
    }

    private const val INITIALIZE = 3
    private const val ADD_TAB = 4
    private const val DELETE_TAB = 5
    private const val ENTRY_KEY = "heartbeat.handoff.taskbar"
    private const val TOOLWINDOW = 0x80
    private const val APPWINDOW = 0x40000
}
