package io.aequicor.heartbeat.platform.desktop

/** Entry point of development runs and distributions; release launchers always use MainKt. */
fun main(args: Array<String>) {
    if (handleWindowRuntimeProbe(args)) return
    launchHeartbeat(isDevelopment = true)
}
