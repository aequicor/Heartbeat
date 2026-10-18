package io.aequicor.heartbeat.feature.worktreemode.impl.data

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A single read-only Git process for one immutable search snapshot. Never evaluates filters or merge drivers. */
internal class GitTextBatch(source: String, private val maxBytes: Long) : AutoCloseable {
    private val process = ProcessBuilder(
        "git",
        "--no-optional-locks",
        "-c",
        "core.fsmonitor=false",
        "cat-file",
        "--batch",
    )
        .directory(Path.of(source).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
            environment().keys.filter { it.startsWith("GIT_") }.forEach { environment().remove(it) }
            environment()["GIT_NO_REPLACE_OBJECTS"] = "1"
            environment()["GIT_NO_LAZY_FETCH"] = "1"
        }.start()
    private val input = process.inputStream.buffered()
    private val exit = process.onExit().orTimeout(CLOSE_TIMEOUT, TimeUnit.MILLISECONDS).whenComplete { _, error ->
        if (error != null && process.isAlive) process.destroyForcibly()
    }

    fun read(entry: GitEntry): String? {
        if (entry.mode !in setOf("100644", "100755")) return null
        process.outputStream.write("${entry.objectId}\n".encodeToByteArray())
        process.outputStream.flush()
        val header = header().split(' ')
        val size = header.getOrNull(2)?.toLongOrNull() ?: error("SandboxGitFailed")
        check(size >= 0 && header.getOrNull(1) == "blob") { "SandboxGitFailed" }
        return if (size > maxBytes) {
            input.skipNBytes(size + 1)
            null
        } else {
            val bytes = input.readNBytes(size.toInt())
            check(bytes.size.toLong() == size && input.read() == LINE_FEED) { "SandboxGitFailed" }
            val text = bytes.decodeToString()
            text.takeIf { 0.toByte() !in bytes && it.encodeToByteArray().contentEquals(bytes) }
        }
    }

    private fun header(): String = buildString {
        var next = input.read()
        while (next != LINE_FEED) {
            check(next >= 0 && length < MAX_HEADER) { "SandboxGitFailed" }
            append(next.toChar())
            next = input.read()
        }
    }

    override fun close() {
        try {
            process.outputStream.close()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(CLOSE_TIMEOUT, TimeUnit.MILLISECONDS)
            exit.cancel(false)
            input.close()
        }
    }

    private companion object {
        const val MAX_HEADER = 128
        const val LINE_FEED = 10
        const val CLOSE_TIMEOUT = 60_000L
    }
}
