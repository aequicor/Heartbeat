package io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker

import io.aequicor.heartbeat.core.logging.Log
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the Java launcher's argument file until process exit: starting a process does not mean it has read the file.
 * Failed starts clean up synchronously. Arguments use the native launcher's encoding, not Java's UTF-8 default.
 */
internal fun startJavaWorker(java: Path, arguments: List<String>, start: (List<String>) -> Process): Process {
    val file = Files.createTempFile("heartbeat-plantuml-", ".args")
    var isStarted = false
    try {
        val charset = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()))
        Files.writeString(file, arguments.joinToString("\n", transform = ::quoteJavaArgument), charset)
        val process = start(listOf(java.toString(), "@$file"))
        process.onExit().thenRun { deleteArguments(file) }
        isStarted = true
        return process
    } finally {
        if (!isStarted) deleteArguments(file)
    }
}

/** Quoting follows the Java launcher grammar, independently of the host shell's command-line escaping. */
private fun quoteJavaArgument(argument: String): String = "\"" + argument
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
    .replace("\u000c", "\\f") + "\""

private fun deleteArguments(file: Path) {
    try {
        Files.deleteIfExists(file)
    } catch (error: IOException) {
        log.w(error) { "Could not remove the PlantUML worker argument file" }
    }
}

private val log = Log.tag("WorkerJavaArguments")
