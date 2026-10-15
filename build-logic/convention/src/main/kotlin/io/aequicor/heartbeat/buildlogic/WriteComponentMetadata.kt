package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

/** Pins the exact portable component archive published alongside a release, without copying it into the app. */
@CacheableTask
abstract class WriteComponentMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    @get:Input abstract val downloadUrl: Property<String>

    @get:Input abstract val version: Property<String>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    /** Writes timestamp-free properties; their SHA comes from the finished archive, never metadata from a server. */
    @TaskAction
    fun write() {
        val url = downloadUrl.get()
        val revision = version.get()
        require(url.startsWith("https://") && url.none { it == '\n' || it == '\r' || it == '\\' })
        require(revision.matches(Regex("[A-Za-z0-9._-]+")))
        val file = artifact.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("version=$revision\nurl=$url\nsha256=$sha256\nsize=${file.length()}\n")
    }
}
