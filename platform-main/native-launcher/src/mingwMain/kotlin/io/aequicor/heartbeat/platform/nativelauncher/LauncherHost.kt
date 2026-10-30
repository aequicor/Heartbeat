@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GetNativeSystemInfo
import platform.windows.SYSTEM_INFO

internal actual fun launcherHostTarget(): String = memScoped {
    val info = alloc<SYSTEM_INFO>()
    GetNativeSystemInfo(info.ptr)
    if (info.wProcessorArchitecture.toInt() == PROCESSOR_ARCHITECTURE_ARM64) "windows-arm64" else "windows-x64"
}

private const val PROCESSOR_ARCHITECTURE_ARM64 = 12
