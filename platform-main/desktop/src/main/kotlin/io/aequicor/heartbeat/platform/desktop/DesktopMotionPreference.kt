package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.core.logging.Log
import java.util.concurrent.CancellationException

/** Reads the host accessibility preference; a failed probe conservatively disables decorative pulses. */
internal fun isDesktopMotionReduced(): Boolean = try {
    when {
        Platform.isWindows() -> {
            val enabled = IntByReference()
            check(motionUser.SystemParametersInfoW(GET_CLIENT_AREA_ANIMATION, 0, enabled, 0)) {
                "Cannot query client-area animation: ${Native.getLastError()}"
            }
            enabled.value == 0
        }

        Platform.isMac() -> {
            var isReduced = true
            motionCocoa.onMainThread {
                val type = NativeLibrary.getInstance("objc").getFunction("objc_getClass")
                    .invokePointer(arrayOf("NSWorkspace"))
                isReduced = motionCocoa.boolean(
                    motionCocoa.pointer(type, "sharedWorkspace"),
                    "accessibilityDisplayShouldReduceMotion",
                )
            }
            isReduced
        }

        else -> true
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.tag("DesktopMotion").w(e) { "Motion preference unavailable; pulses disabled" }
    true
} catch (e: LinkageError) {
    Log.tag("DesktopMotion").w(e) { "Native motion preference unavailable; pulses disabled" }
    true
}

private val motionUser by lazy { Native.load("user32", MotionUser32::class.java) }
private val motionCocoa by lazy { MacWindowAccess() }
private const val GET_CLIENT_AREA_ANIMATION = 0x1042

@Suppress("FunctionNaming") // Fixed Win32 ABI entry point.
private interface MotionUser32 : StdCallLibrary {
    fun SystemParametersInfoW(action: Int, parameter: Int, value: IntByReference, flags: Int): Boolean
}
