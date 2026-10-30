@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform

internal actual fun launcherHostTarget(): String = when (Platform.osFamily) {
    OsFamily.MACOSX -> if (Platform.cpuArchitecture == CpuArchitecture.ARM64) "macos-arm64" else "macos-x64"
    OsFamily.LINUX -> "linux-x64"
    else -> error("Unsupported Unix launcher host")
}
