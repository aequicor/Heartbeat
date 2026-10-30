@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.unix.hb_bootstrap_environment
import io.aequicor.heartbeat.platform.nativelauncher.unix.hb_bootstrap_executable
import io.aequicor.heartbeat.platform.nativelauncher.unix.hb_bootstrap_spawn
import io.aequicor.heartbeat.platform.nativelauncher.unix.hb_bootstrap_wait
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.SIGKILL
import platform.posix.errno
import platform.posix.kill

/** Owned POSIX child: argv is never joined into a shell command; standard streams are inherited for workers. */
internal class UnixChild private constructor(val pid: Int) {
    private var exitCode: Int? = null

    fun poll(): Int? = waitFor(blocking = false)

    fun waitForExit(): Int {
        while (true) waitFor(blocking = true)?.let { return it }
    }

    private fun waitFor(blocking: Boolean): Int? = memScoped {
        exitCode?.let { return@memScoped it }
        val code = alloc<IntVar>()
        val result = hb_bootstrap_wait(pid, if (blocking) 1 else 0, code.ptr)
        check(result >= 0) { "Cannot wait for launcher child: errno=${-result}" }
        if (result == 0) null else code.value.also { exitCode = it }
    }

    /** Cancellation before handoff stops only this unreaped child, whose PID cannot yet be reused. */
    fun terminate() {
        if (poll() != null) return
        check(kill(pid, SIGKILL) == 0) { "Cannot stop launcher child: errno=$errno" }
        waitForExit()
    }

    companion object {
        fun start(executable: Path, args: List<String>, extra: Map<String, String> = emptyMap()): UnixChild =
            memScoped {
                val argv = listOf(executable.toString()) + args
                require(argv.none { '\u0000' in it }) { "NUL in launcher argument" }
                require(extra.all { (key, value) -> '=' !in key && '\u0000' !in key && '\u0000' !in value }) {
                    "Invalid launcher environment"
                }
                val existing = nativeEnvironment().filter { it.substringBefore('=') !in extra }
                val env = existing + extra.map { (key, value) -> "$key=$value" }
                val argumentPointers = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
                argv.forEachIndexed { i, argument -> argumentPointers[i] = argument.cstr.getPointer(this) }
                argumentPointers[argv.size] = null
                val environmentPointers = allocArray<CPointerVar<ByteVar>>(env.size + 1)
                env.forEachIndexed { i, entry -> environmentPointers[i] = entry.cstr.getPointer(this) }
                environmentPointers[env.size] = null
                val pid = hb_bootstrap_spawn(executable.toString(), argumentPointers, environmentPointers)
                check(pid > 0) { "Cannot start provisioned application: errno=${-pid}" }
                launcherLog.i("Launcher") { "Started provisioned application" }
                UnixChild(pid)
            }

        private fun nativeEnvironment(): List<String> {
            val pointer = hb_bootstrap_environment() ?: return emptyList()
            val entries = ArrayList<String>()
            var index = 0
            while (true) {
                val value = pointer[index++] ?: break
                entries += value.toKString()
            }
            return entries
        }
    }
}

/** Absolute outer launcher path is passed separately from jpackage.app-path, which remains the inner JVM executable. */
internal fun nativeLauncherExecutable(): Path {
    val buffer = ByteArray(EXECUTABLE_PATH_BYTES)
    val length = buffer.usePinned { hb_bootstrap_executable(it.addressOf(0), buffer.size.convert()) }
    check(length > 0) { "Cannot resolve native launcher executable" }
    return FileSystem.SYSTEM.canonicalize(buffer.decodeToString(0, length).toPath())
}

private const val EXECUTABLE_PATH_BYTES = 4096
