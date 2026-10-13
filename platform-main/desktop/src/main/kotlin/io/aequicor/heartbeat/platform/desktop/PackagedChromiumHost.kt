package io.aequicor.heartbeat.platform.desktop

private const val CHROMIUM_HOST_ARGUMENT = "--heartbeat-chromium-host"
private const val CHROMIUM_HOST_MAIN =
    "io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host.ChromiumHostMainKt"

/**
 * Chromium browsers start the native-messaging host through the launcher wrapper the bridge writes at
 * registration; the wrapper runs this launcher in the private host mode, before logging, credentials, DI or Compose
 * initialize. The host relays the browser's standard streams to the app socket. Reflection keeps the desktop host
 * independent of feature implementations in the dependency graph.
 */
internal fun runChromiumHost(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != CHROMIUM_HOST_ARGUMENT) return false
    Class.forName(CHROMIUM_HOST_MAIN).getMethod("main", Array<String>::class.java)
        .invoke(null, arguments.copyOfRange(1, arguments.size))
    return true
}
