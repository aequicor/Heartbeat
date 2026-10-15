package io.aequicor.heartbeat.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test

/** Serializes native test workers that share the clipboard, desktop focus, physical input and screen capture. */
internal abstract class NativeDesktopTestService : BuildService<BuildServiceParameters.None>

/**
 * Grants this task exclusive desktop access among participating tasks in the same Gradle invocation.
 * A single worker also prevents test classes within this task from racing over the system clipboard or focus.
 * Compilation and tasks that do not use desktop resources remain parallel.
 */
fun Test.useNativeDesktop() {
    val desktop = project.gradle.sharedServices.registerIfAbsent(
        "heartbeatNativeDesktopTests",
        NativeDesktopTestService::class.java,
    ) {
        maxParallelUsages.set(1)
    }
    maxParallelForks = 1
    usesService(desktop)
}
