package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Entry point of the native-messaging host process. Chrome starts the launcher wrapper written at registration,
 * which runs the app launcher (or `java` in development) in the private `--heartbeat-chromium-host` mode; the
 * desktop entry point hands over the remaining arguments before logging, DI or Compose initialize.
 * Standard output carries only frames to Chrome, so stray prints are redirected to standard error, where the
 * host also writes its warnings.
 */
public fun main(arguments: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val toBrowser = BufferedOutputStream(FileOutputStream(FileDescriptor.out))
    val diagnostics = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
    System.setOut(diagnostics)
    Log.init(isDebug = false, sinks = listOf(StandardErrorSink(diagnostics)))
    val parsed = HostArguments.parse(arguments.toList())
    val relay = NativeHostRelay(
        browserInput = BufferedInputStream(FileInputStream(FileDescriptor.`in`)),
        browserOutput = toBrowser,
        origin = parsed.origin,
        connector = UnixSocketConnector(parsed.socket),
    )
    exitProcess(relay.run())
}

/** Warnings and errors of the host on standard error; Chrome keeps them in its own log. */
private class StandardErrorSink(private val stream: PrintStream) : LogSink {
    override fun write(level: LogLevel, tag: String, error: Throwable?, message: String) {
        if (level < LogLevel.WARNING) return
        val trace = error?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        stream.write("${level.name} $tag: $message$trace\n".toByteArray(Charsets.UTF_8))
        stream.flush()
    }
}
