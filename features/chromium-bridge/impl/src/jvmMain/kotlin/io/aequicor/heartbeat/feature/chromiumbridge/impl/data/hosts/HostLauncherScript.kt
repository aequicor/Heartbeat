package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import java.nio.file.Path

/**
 * The executable a browser starts as the native-messaging host. Browsers pass no custom arguments, so the
 * wrapper carries the socket and the launch mode; it forwards the browser's own arguments (the extension origin
 * and, on Windows, the parent window). JVM output goes to standard error, standard output carries only frames.
 */
internal object HostLauncherScript {
    /** Host process JVM options: small heap, no display, VM messages away from the frames. */
    val jvmOptions: List<String> = listOf(
        "-Xmx96m",
        "-XX:+UseSerialGC",
        "-XX:-UsePerfData",
        "-XX:+DisplayVMOutputToStderr",
        "-Djava.awt.headless=true",
    )

    /** Main class of the host for development launches. */
    const val HOST_MAIN = "io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host.ChromiumHostMainKt"

    /** Private launcher argument routed by the desktop entry point. */
    const val HOST_ARGUMENT = "--heartbeat-chromium-host"

    private const val WRAPPER_NAME = "heartbeat-chromium-host"

    /** Wrapper file name; development and installed builds keep separate wrappers in the shared data directory. */
    fun fileName(launcher: HostLauncher, isWindows: Boolean): String {
        val base = if (launcher is HostLauncher.Development) "$WRAPPER_NAME-dev" else WRAPPER_NAME
        return if (isWindows) "$base.bat" else base
    }

    /** Argument file of a development launch: JVM options, classpath and main class, in the Java launcher grammar. */
    fun argumentFile(launcher: HostLauncher.Development): String {
        val arguments = jvmOptions + listOf("-cp", launcher.classpath, HOST_MAIN)
        return arguments.joinToString("\n", transform = ::quoteJavaArgument)
    }

    /** Wrapper contents; [argumentFile] is required for a development launcher. */
    fun script(launcher: HostLauncher, socket: Path, isWindows: Boolean, argumentFile: Path? = null): String {
        val command = when (launcher) {
            is HostLauncher.Packaged -> listOf(launcher.launcher.toString(), HOST_ARGUMENT, socket.toString())

            is HostLauncher.Development ->
                listOf(launcher.java.toString(), "@" + checkNotNull(argumentFile), socket.toString())

            is HostLauncher.Unavailable -> error("No launcher to write: ${launcher.problem}")
        }
        val toolOptions = if (launcher is HostLauncher.Packaged) jvmOptions.joinToString(" ") else null
        return if (isWindows) batch(command, toolOptions) else shell(command, toolOptions)
    }

    private fun shell(command: List<String>, toolOptions: String?): String = buildString {
        append("#!/bin/sh\n")
        append("# Written by Heartbeat: starts the native-messaging host of the Chromium bridge.\n")
        if (toolOptions != null) {
            append("JAVA_TOOL_OPTIONS=${shellQuote(toolOptions)}\n")
            append("export JAVA_TOOL_OPTIONS\n")
        }
        append("exec ").append(command.joinToString(" ", transform = ::shellQuote)).append(" \"\$@\"\n")
    }

    private fun batch(command: List<String>, toolOptions: String?): String = buildString {
        append("@echo off\r\n")
        append("rem Written by Heartbeat: starts the native-messaging host of the Chromium bridge.\r\n")
        if (toolOptions != null) append("set \"JAVA_TOOL_OPTIONS=${batchEscape(toolOptions)}\"\r\n")
        append(command.joinToString(" ") { "\"" + batchEscape(it) + "\"" }).append(" %*\r\n")
    }

    /** POSIX single quotes: nothing inside is interpreted, a quote itself is closed, escaped and reopened. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Windows paths cannot contain `"`; percent signs would expand, so they are doubled. */
    private fun batchEscape(value: String): String {
        require('"' !in value && '\n' !in value && '\r' !in value) { "Value cannot be quoted for cmd" }
        return value.replace("%", "%%")
    }

    private fun quoteJavaArgument(argument: String): String = "\"" + argument
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""
}
