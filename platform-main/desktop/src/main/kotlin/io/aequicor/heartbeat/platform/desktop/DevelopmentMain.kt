package io.aequicor.heartbeat.platform.desktop

/** Entry point of development runs and distributions; release launchers always use MainKt. */
fun main(arguments: Array<String>) {
    if (runPackagedBuildWorker(arguments)) return
    if (runPackagedPlantUmlWorker(arguments)) return
    if (handleWindowRuntimeProbe(arguments)) return
    if (handleComputerUseIndicatorProbe(arguments)) return
    launchHeartbeat(isDevelopment = true)
}
