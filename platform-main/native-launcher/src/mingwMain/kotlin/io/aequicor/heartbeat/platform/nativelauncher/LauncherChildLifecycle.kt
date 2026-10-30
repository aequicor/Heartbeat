@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapProvisioner
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.windowsHttpTransport
import kotlinx.coroutines.Job
import okio.FileSystem
import okio.Path
import platform.windows.CloseHandle
import platform.windows.HANDLE
import platform.windows.Sleep
import platform.windows.TerminateProcess
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject

/** GUI teardown does not abandon its provisioning worker or owned JVM, and preserves the JVM exit status. */
internal fun finishLauncherChild(job: Job?, child: HANDLE?, isCancelled: Boolean, handoffDirectory: Path): Int {
    job?.cancel()
    while (job?.isCompleted == false) Sleep(CLEANUP_POLL_MS)
    val code = child?.let { process ->
        try {
            if (isCancelled) cancelLauncherChild(process)
            waitForProcessExit(process)
        } finally {
            CloseHandle(process)
        }
    } ?: 0
    FileSystem.SYSTEM.deleteRecursively(handoffDirectory, mustExist = false)
    return if (isCancelled) 0 else code
}

private fun cancelLauncherChild(process: HANDLE) {
    if (WaitForSingleObject(process, CANCELLATION_GRACE_MS) == WAIT_OBJECT_0) return
    launcherLog.w("Launcher", IllegalStateException("Child did not acknowledge cancellation")) {
        "Forcing application exit after cancellation grace period"
    }
    check(TerminateProcess(process, 1u) != 0 || WaitForSingleObject(process, 0u) == WAIT_OBJECT_0) {
        "Cannot stop launcher child"
    }
}

private const val CANCELLATION_GRACE_MS = 30_000u
private const val CLEANUP_POLL_MS = 10u

/** Network transport stays owned by the provisioning job and closes on completion or cancellation. */
internal suspend fun provisionLauncherImage(
    config: LauncherConfig,
    store: ImageStore,
    onProgress: (ProvisionProgress) -> Unit,
    isCancelled: () -> Boolean,
): ProvisionedImage {
    require(config.trustedKeys.isNotEmpty()) { "Bootstrap public key is not configured" }
    return config.currentImage(store) ?: windowsHttpTransport()
        .use { transport ->
            BootstrapProvisioner(
                store = store,
                transport = transport,
                manifestUrl = config.manifestUrl,
                signatureUrl = config.signatureUrl,
                trustedKeys = config.trustedKeys,
                expectedVersion = config.version,
                expectedTarget = config.target,
                expectedChannel = config.channel,
            ).provision(onProgress, isCancelled)
        }
}
