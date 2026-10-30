@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_start
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.Path
import platform.windows.HWND

/** Starts only the x64 image in this x64 process; ARM64 keeps the jpackage child process. */
internal fun startEmbeddedJvm(
    window: HWND,
    image: Path,
    executable: String,
    target: String,
    args: List<String>,
    density: Double,
): Boolean {
    if (target != "windows-x64") return false
    val options = try {
        val app = image.resolve("app")
        val name = executable.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.')
        parsePackagedJvmConfiguration(
            FileSystem.SYSTEM.read(app.resolve("$name.cfg")) { readUtf8() },
            app.toString(),
        ).windowsJvmOptions(executable)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        launcherLog.w("EmbeddedJvm", error) { "Cannot prepare embedded JVM; using packaged child" }
        return false
    }
    val previousPath = launcherEnvironment("PATH")
    var started = false
    var pathChanged = false
    try {
        installEmbeddedCover(window, density)
        setEmbeddedSearchPath("${image.resolve("app")};${previousPath.orEmpty()}")
        pathChanged = true
        started = invokeEmbeddedJvm(window, image, options, args)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        launcherLog.w("EmbeddedJvm", error) { "Cannot initialize embedded host; using packaged child" }
    } finally {
        if (!started) {
            if (pathChanged) setEmbeddedSearchPath(previousPath)
            io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_close_cover()
        }
    }
    return started
}

private fun invokeEmbeddedJvm(window: HWND, image: Path, options: List<String>, args: List<String>): Boolean =
    memScoped {
        val nativeOptions = allocArray<CPointerVar<kotlinx.cinterop.ByteVar>>(options.size)
        options.forEachIndexed { index, option -> nativeOptions[index] = option.cstr.getPointer(this) }
        val nativeArguments = allocArray<CPointerVar<UShortVar>>(args.size.coerceAtLeast(1))
        args.forEachIndexed { index, argument -> nativeArguments[index] = argument.wcstr.getPointer(this) }
        hb_jvm_start(
            window,
            image.resolve("runtime").toString().wcstr.getPointer(this),
            nativeOptions,
            options.size,
            nativeArguments,
            args.size,
        ) != 0
    }

/** The JNI worker, rather than a second process, owns the application lifetime. */
internal fun advanceEmbeddedHost(window: HWND?): Boolean {
    if (io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_exit_code() >= 0) {
        platform.windows.DestroyWindow(window)
        return true
    }
    if (io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_cancel_expired() != 0) {
        launcherLog.w("EmbeddedJvm", IllegalStateException("JVM cancellation timed out")) {
            "Stopping the process after the cooperative cancellation grace period"
        }
        check(platform.windows.TerminateProcess(platform.windows.GetCurrentProcess(), 1u) != 0)
    }
    return io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_is_mounted() != 0
}

internal fun handleEmbeddedParentMessage(
    message: platform.windows.UINT,
    wParam: platform.windows.WPARAM,
    lParam: platform.windows.LPARAM,
): platform.windows.LRESULT? {
    if (message.toInt() != platform.windows.WM_PARENTNOTIFY) return null
    if (wParam.toInt() and WINDOWS_LOW_WORD == platform.windows.WM_CREATE) {
        io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_child_created(lParam.toCPointer())
    }
    return 0L
}

private const val WINDOWS_LOW_WORD = 0xffff

private fun setEmbeddedSearchPath(path: String?) = memScoped {
    check(platform.windows.SetEnvironmentVariableW("PATH".wcstr.ptr, path?.wcstr?.ptr) != 0) {
        "Cannot configure packaged native library search path"
    }
}

internal fun embeddedJvmCanFallback(): Boolean =
    io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_can_fallback() != 0

/** No fallback can select slots a second time after the first selector entered main. */
internal fun fallbackEmbeddedJvm(
    executable: String,
    args: List<String>,
    previousPath: String?,
): platform.windows.HANDLE? {
    check(io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_prepare_fallback() != 0) {
        "Embedded JVM has not released ownership for fallback"
    }
    setEmbeddedSearchPath(previousPath)
    launcherLog.w("EmbeddedJvm", IllegalStateException("Embedded JVM failed before selector startup")) {
        "Starting packaged child after embedded JVM shutdown"
    }
    return checkNotNull(launchProcess(executable, args)) { "Cannot launch packaged fallback" }
}

internal fun isEmbeddedContentPresented(): Boolean =
    io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_is_presented() != 0
