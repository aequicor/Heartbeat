package io.aequicor.heartbeat.platform.desktop

/**
 * Entry point of development runs and distributions; published release launchers use MainKt, the testing flavor
 * TestingMainKt. A new worker or probe argument is handled in all three.
 */
fun main(arguments: Array<String>) {
    if (runPackagedBuildWorker(arguments)) return
    if (runPackagedPlantUmlWorker(arguments)) return
    if (runPackagedHarnessCompilerWorker(arguments)) return
    if (runChromiumHost(arguments)) return
    if (handleWindowRuntimeProbe(arguments)) return
    if (handleComputerUseIndicatorProbe(arguments)) return
    if (handleHarnessCompilerProbe(arguments)) return
    val workerExit = handleProcessWorker(arguments, isDevelopment = true)
    if (workerExit != null) kotlin.system.exitProcess(workerExit)
    launchHeartbeat(isDevelopment = true)
}
