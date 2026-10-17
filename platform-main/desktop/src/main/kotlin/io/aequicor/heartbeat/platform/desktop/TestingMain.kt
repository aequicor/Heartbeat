package io.aequicor.heartbeat.platform.desktop

/**
 * Entry point of the testing flavor (`-Pheartbeat.flavor=testing`): a release build for end-to-end checks whose
 * credentials live in the local development files, so a test run never asks for Keychain, DPAPI or Secret Service.
 * Only the flavored build uses it as the launcher class; release workflows never pass the flavor, and the
 * protected [main] of MainKt stays the release launcher. Everything else matches a release launch.
 */
fun main(arguments: Array<String>) {
    if (runPackagedBuildWorker(arguments)) return
    if (runPackagedPlantUmlWorker(arguments)) return
    if (runPackagedHarnessCompilerWorker(arguments)) return
    if (runChromiumHost(arguments)) return
    if (handleWindowRuntimeProbe(arguments)) return
    if (handleComputerUseIndicatorProbe(arguments)) return
    if (handleHarnessCompilerProbe(arguments)) return
    launchHeartbeat(isDevelopment = false, hasLocalSecrets = true)
}
