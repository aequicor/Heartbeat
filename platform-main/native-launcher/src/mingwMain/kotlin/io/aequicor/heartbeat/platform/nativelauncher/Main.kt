@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_start_process
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.Path
import okio.Path.Companion.toPath
import platform.windows.CloseHandle
import platform.windows.GetExitCodeProcess
import platform.windows.GetModuleFileNameW
import platform.windows.HANDLE
import platform.windows.INFINITE
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject
import kotlin.system.exitProcess

/**
 * Точка входа Native Heartbeat Launcher: полноценное окно до старта JVM, provisioning зависимостей,
 * запуск приложения из проверенного локального image. Повторный запуск — offline, без манифеста.
 * Worker-процессы (`--heartbeat-*`) запускаются напрямую из image, без launcher window.
 */
fun main(args: Array<String>) {
    val config = LauncherConfig.load()
    val store = ImageStore(config.imageRoot)
    val offline = config.currentImage(store)
    if (args.any { it.startsWith("--heartbeat-") }) {
        val image = offline ?: error("No provisioned image for a worker process")
        exitProcess(launchAndWait(image, args.toList(), config.channel))
    }
    val window = LauncherWindow(config, store, args.toList())
    LauncherWindowRegistry.instance = window
    exitProcess(window.run(offline))
}

/** Запускает `Heartbeat.exe` из активированного image и ждёт завершения; exit code приложения сохраняется. */
private fun launchAndWait(image: ProvisionedImage, args: List<String>, channel: String): Int {
    val executable = image.directory.resolve(BootstrapLayout.forPlatform("windows", channel).executable).toString()
    val process = launchProcess(executable, args) ?: return 1
    return try {
        waitForProcessExit(process)
    } finally {
        CloseHandle(process)
    }
}

internal fun processExitCode(process: HANDLE): Int = memScoped {
    if (process == io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_thread()) {
        return@memScoped io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_exit_code()
    }
    val code = alloc<UIntVar>()
    check(GetExitCodeProcess(process, code.ptr) != 0) { "Cannot read application exit code" }
    code.value.toInt()
}

internal fun waitForProcessExit(process: HANDLE): Int {
    check(WaitForSingleObject(process, INFINITE) == WAIT_OBJECT_0) { "Cannot wait for application exit" }
    return processExitCode(process)
}

/** Absolute Unicode outer path, independent from the immutable JVM image and jpackage.app-path. */
internal fun nativeLauncherExecutable(): Path = memScoped {
    val buffer = allocArray<UShortVar>(WINDOWS_PATH_CAPACITY)
    val length = GetModuleFileNameW(null, buffer, WINDOWS_PATH_CAPACITY.toUInt())
    check(length > 0u && length < WINDOWS_PATH_CAPACITY.toUInt()) { "Cannot resolve native launcher path" }
    buffer.toKString().toPath()
}

private const val WINDOWS_PATH_CAPACITY = 32768

/** CreateProcessW preserves argv and inherits only duplicate stdin/stdout/stderr handles. */
internal fun launchProcess(executable: String, args: List<String> = emptyList()): HANDLE? = memScoped {
    val process = alloc<kotlinx.cinterop.COpaquePointerVar>()
    val result = hb_start_process(
        executable.wcstr.getPointer(this),
        windowsCommandLine(executable, args).wcstr.getPointer(this),
        process.ptr,
    )
    if (result != 0u) {
        launcherLog.w("Launcher", IllegalStateException("CreateProcessW error=$result")) { "Cannot start owned child" }
        null
    } else {
        process.value
    }
}
