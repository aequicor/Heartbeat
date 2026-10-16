package io.aequicor.heartbeat.feature.scheduler.impl

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.Base64
import kotlin.system.exitProcess

/** A native argv and exit-code probe; stdout is a Base64 protocol, stderr must not change its exit code. */
internal object LocalCommandTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        // Borrowed process streams: closing stdout can also close stderr when Windows shares their handle.
        val output = FileOutputStream(FileDescriptor.out)
        args.drop(1).forEach {
            val line = "argv:" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) + "\n"
            output.write(line.toByteArray(Charsets.UTF_8))
        }
        FileOutputStream(FileDescriptor.err).write("native stderr\n".toByteArray(Charsets.UTF_8))
        exitProcess(args[0].toInt())
    }

    fun arguments(exitCode: Int, words: List<String>): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        listOf(LocalCommandTestProcess::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        },
        LocalCommandTestProcess::class.java.name,
        exitCode.toString(),
    ) + words
}
