package io.aequicor.heartbeat.platform.desktop

import com.jetbrains.JBR
import io.aequicor.heartbeat.core.logging.Log

/** Packaging gate: native window controls must be available in the runtime used to create the app image. */
fun main() {
    verifyNativeWindowRuntime()
}

/** Also available in packaged launchers, so the shrunken application and linked runtime can be smoke-tested. */
internal fun handleWindowRuntimeProbe(args: Array<String>): Boolean {
    if (!args.contentEquals(arrayOf("--verify-window-runtime"))) return false
    verifyNativeWindowRuntime()
    return true
}

private fun verifyNativeWindowRuntime() {
    Log.init(isDebug = true)
    check(JBR.isWindowDecorationsSupported()) { "Desktop distributions require JBR with WindowDecorations support" }
    Log.tag("WindowRuntimeProbe").i { "Native caption runtime verified" }
}
