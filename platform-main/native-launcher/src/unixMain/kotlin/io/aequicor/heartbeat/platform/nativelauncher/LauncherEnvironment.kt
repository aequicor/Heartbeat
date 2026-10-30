@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun launcherEnvironment(name: String): String? = getenv(name)?.toKString()
