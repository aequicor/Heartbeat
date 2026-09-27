package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

/** Fetches the pinned standalone distribution before Compose assembles installer resources. */
abstract class PreparePiRuntime : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val asset: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun prepare() {
        val archive = temporaryDir.resolve(asset.get())
        val connection = URI(
            "https://github.com/earendil-works/pi/releases/download/v${version.get()}/${asset.get()}",
        ).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        connection.getInputStream().use { input -> archive.outputStream().use(input::copyTo) }
        val hash = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "Pi archive checksum mismatch; refusing to package executable" }
        val zipped = asset.get().endsWith(".zip")
        val tree = if (zipped) archives.zipTree(archive) else archives.tarTree(archive)
        files.sync {
            from(tree)
            into(outputDirectory.dir("common/pi"))
            includeEmptyDirs = false
            if (!zipped) eachFile {
                relativePath = RelativePath(!isDirectory, *relativePath.segments.drop(1).toTypedArray())
            }
        }
        val payload = outputDirectory.dir("common/pi").get().asFile
        val executable = payload.resolve(if (zipped) "pi.exe" else "pi")
        check(executable.isFile) { "Pi archive did not contain its executable" }
        check(zipped || executable.setExecutable(true, false)) { "Cannot mark bundled Pi executable" }
        payload.resolve("LICENSE").writeText(
            """
            MIT License

            Copyright (c) 2025 Mario Zechner

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
            """.trimIndent() + "\n",
        )
    }
}