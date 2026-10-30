@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.wcstr
import platform.windows.GetEnvironmentVariableW

/** Narrow CRT getenv can depend on its code page; APPDATA and other paths must retain their UTF-16 characters. */
internal actual fun launcherEnvironment(name: String): String? = memScoped {
    val buffer = allocArray<UShortVar>(ENVIRONMENT_CAPACITY)
    val length = GetEnvironmentVariableW(name.wcstr.ptr, buffer, ENVIRONMENT_CAPACITY.toUInt())
    check(
        length < ENVIRONMENT_CAPACITY.toUInt(),
    ) {
        "Native launcher environment value exceeds the Windows limit: $name"
    }
    if (length == 0u) null else buffer.toKString()
}

private const val ENVIRONMENT_CAPACITY = 32768

/** The inner JVM path and the external restart path have distinct ownership and must not be interchanged. */
internal fun configureLauncherEnvironment(handoffDirectory: okio.Path) = memScoped {
    check(
        platform.windows.SetEnvironmentVariableW(
            "HEARTBEAT_HANDOFF_DIR".wcstr.ptr,
            handoffDirectory.toString().wcstr.ptr,
        ) != 0,
    ) { "Cannot set handoff directory" }
    check(
        platform.windows.SetEnvironmentVariableW(
            "HEARTBEAT_NATIVE_LAUNCHER".wcstr.ptr,
            nativeLauncherExecutable().toString().wcstr.ptr,
        ) != 0,
    ) { "Cannot set native launcher path" }
}
