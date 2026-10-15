package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.process.ExecOperations
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject

/**
 * Recompresses the completed disk image without changing its filesystem, app signature or installation layout.
 * ULMO (LZMA) is understood by macOS 10.15 and later, including every Apple Silicon system. This is a final action
 * of packageReleaseDmg itself: Gradle records the final checksum and subsequent builds can stay up to date.
 */
abstract class CompressReleaseDmg : Action<Task> {
    abstract val imageDirectory: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    /** Publishes the smaller image only after hdiutil verifies it; any failed conversion retains the original. */
    override fun execute(task: Task) {
        val directory = imageDirectory.get().asFile.toPath()
        val original = Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".dmg") }.toList().single()
        }
        val staging = Files.createTempDirectory(directory, ".compress-")
        val compressed = staging.resolve("compressed.dmg")
        try {
            exec.exec {
                commandLine(
                    "hdiutil",
                    "convert",
                    original.toString(),
                    "-format",
                    "ULMO",
                    "-o",
                    compressed.toString(),
                    "-quiet",
                )
            }
            exec.exec { commandLine("hdiutil", "verify", compressed.toString(), "-quiet") }
            val saved = Files.size(original) - Files.size(compressed)
            if (saved > 0) {
                Files.move(compressed, original, ATOMIC_MOVE, REPLACE_EXISTING)
                task.logger.lifecycle("Release DMG: saved {} bytes with LZMA compression", saved)
            }
        } finally {
            Files.deleteIfExists(compressed)
            Files.delete(staging)
        }
    }
}
