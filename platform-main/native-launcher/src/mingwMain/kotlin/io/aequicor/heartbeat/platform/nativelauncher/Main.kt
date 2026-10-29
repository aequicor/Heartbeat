@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import kotlinx.cinterop.alloc
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.wcstr
import platform.windows.*
import kotlin.system.exitProcess

/**
 * Точка входа Native Heartbeat Launcher: полноценное окно до старта JVM, provisioning зависимостей,
 * запуск приложения из проверенного локального image. Повторный запуск — offline, без манифеста.
 * Worker-процессы (`--heartbeat-*`) запускаются напрямую из image, без launcher window.
 */
fun main(args: Array<String>) {
    val config = LauncherConfig.load()
    val store = ImageStore(config.imageRoot)
    val offline = store.currentImage()
    if (args.any { it.startsWith("--heartbeat-") }) {
        val image = offline ?: error("No provisioned image for a worker process")
        exitProcess(launchAndWait(image, args.toList()))
    }
    val window = LauncherWindow(config, store)
    LauncherWindowRegistry.instance = window
    window.run(offline)
}

/** Запускает `Heartbeat.exe` из активированного image и ждёт завершения; exit code приложения сохраняется. */
private fun launchAndWait(image: ProvisionedImage, args: List<String>): Int {
    val executable = image.directory.resolve("Heartbeat.exe").toString()
    val process = launchProcess(executable, args) ?: return 1
    WaitForSingleObject(process, INFINITE)
    val code = memScoped {
        val bytes = ByteArray(4)
        bytes.usePinned { pinned ->
            GetExitCodeProcess(process, pinned.addressOf(0).reinterpret())
        }
        bytes[0].toInt() or (bytes[1].toInt() shl 8) or (bytes[2].toInt() shl 16) or (bytes[3].toInt() shl 24)
    }
    CloseHandle(process)
    return code
}

/** CreateProcessW с наследованием консоли; дескриптор процесса остаётся вызывателю, поток закрывается сразу. */
internal fun launchProcess(executable: String, args: List<String> = emptyList()): HANDLE? = memScoped {
    val commandLine = buildString {
        append('"').append(executable).append('"')
        args.forEach { append(' ').append(it) }
    }
    val startup = alloc<STARTUPINFOW>().apply { cb = sizeOf<STARTUPINFOW>().toUInt() }
    val info = alloc<PROCESS_INFORMATION>()
    val created = CreateProcessW(
        executable.wcstr.getPointer(this),
        commandLine.wcstr.getPointer(this),
        null,
        null,
        0,
        0u,
        null,
        null,
        startup.ptr,
        info.ptr,
    )
    if (created == 0) return@memScoped null
    CloseHandle(info.hThread)
    info.hProcess
}
