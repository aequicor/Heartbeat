package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Exports the production Chromium panel with a manifest covering every file. A release builds this artifact
 * once, then other desktop hosts reuse it without running the Wasm compiler, Binaryen or webpack again.
 */
@DisableCachingByDefault(because = "Copies an already built distribution and hashes its files")
abstract class PrepareChromiumPanelArtifact : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val panelDirectory: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun prepare() {
        files.sync {
            from(panelDirectory)
            into(outputDirectory)
            exclude(ChromiumPanelArtifact.MANIFEST_NAME)
        }
        ChromiumPanelArtifact.writeManifest(outputDirectory.get().asFile)
    }
}

/** Rejects missing, incomplete or changed prebuilt panel artifacts before any desktop distribution is copied. */
@DisableCachingByDefault(because = "Every imported artifact must be checked on the consuming host")
abstract class VerifyChromiumPanelArtifact : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val panelDirectory: DirectoryProperty

    @TaskAction
    fun verify() = ChromiumPanelArtifact.verify(panelDirectory.get().asFile)
}

/** Exact file-set and content validation; manifest paths are compared as text and are never resolved as input. */
internal object ChromiumPanelArtifact {
    const val MANIFEST_NAME = "SHA256SUMS"

    fun writeManifest(directory: File) {
        directory.resolve(MANIFEST_NAME).writeText(manifest(directory))
    }

    fun verify(directory: File) {
        val expected = manifest(directory)
        val recorded = directory.resolve(MANIFEST_NAME)
        check(recorded.isFile && recorded.readText() == expected) {
            "Chromium panel artifact is missing its manifest or has incomplete/changed files: $directory"
        }
    }

    private fun manifest(directory: File): String {
        val root = directory.toPath().toAbsolutePath().normalize()
        check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Chromium panel directory is missing: $directory" }
        val files = Files.walk(root).use { paths ->
            paths.filter { path ->
                check(!Files.isSymbolicLink(path)) { "Chromium panel artifact must not contain symlinks: $path" }
                !Files.isDirectory(path, NOFOLLOW_LINKS)
            }.map { path ->
                check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Unsupported Chromium panel entry: $path" }
                val name = root.relativize(path).toString().replace(File.separatorChar, '/')
                check(name.none { it == '\\' || it == '\n' || it == '\r' }) {
                    "Chromium panel file name is not portable: $name"
                }
                name to path.toFile()
            }.toList().toMap() - MANIFEST_NAME
        }
        check(files["panel.js"]?.length()?.let { it > 0 } == true) { "Chromium panel.js is missing or empty" }
        check(files.any { (name, file) -> name.endsWith(".wasm") && file.length() > 0 }) {
            "Chromium panel Wasm payload is missing or empty"
        }
        check(files.any { (name, file) -> name.startsWith("composeResources/") && file.length() > 0 }) {
            "Chromium panel Compose resources are missing or empty"
        }
        return files.toSortedMap().entries.joinToString("\n", postfix = "\n") { (name, file) ->
            "${file.sha256()}  $name"
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
