package io.aequicor.heartbeat.feature.scheduler.impl

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.CountDownLatch

/** A real descendant which leaves its command's process group before the parent is allowed to exit. */
internal object PosixDescendantTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[1])
        if (args[0] == "child") {
            check(Native.load(Platform.C_LIBRARY_NAME, ProcessGroup::class.java).setpgid(0, 0) == 0)
            Files.writeString(directory.resolve("escaped-pid.tmp"), ProcessHandle.current().pid().toString())
            Files.move(directory.resolve("escaped-pid.tmp"), directory.resolve("escaped-pid"))
            CountDownLatch(1).await()
        } else {
            directory.fileSystem.newWatchService().use { watcher ->
                directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
                ProcessBuilder(arguments("child", directory.toString())).inheritIO().start()
                while (!Files.exists(directory.resolve("exit-parent"))) watcher.take().reset()
            }
        }
    }

    fun arguments(role: String, directory: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        listOf(PosixDescendantTestProcess::class.java, Unit::class.java, Native::class.java)
            .joinToString(
                java.io.File.pathSeparator,
            ) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() },
        PosixDescendantTestProcess::class.java.name,
        role,
        directory,
    )

    @Suppress("FunctionNaming") // Native POSIX ABI.
    private interface ProcessGroup : Library {
        fun setpgid(pid: Int, group: Int): Int
    }
}
