@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_native_machine

/** Native payload architecture, even when this x64 executable is emulated on Windows ARM64. */
internal actual fun launcherHostTarget(): String = windowsPayloadTarget(hb_native_machine().toInt())

internal fun windowsPayloadTarget(nativeMachine: Int): String = when (nativeMachine) {
    IMAGE_FILE_MACHINE_ARM64 -> "windows-arm64"
    IMAGE_FILE_MACHINE_AMD64 -> "windows-x64"
    else -> error("Cannot determine supported Windows host architecture: $nativeMachine")
}

private const val IMAGE_FILE_MACHINE_ARM64 = 0xaa64
private const val IMAGE_FILE_MACHINE_AMD64 = 0x8664
