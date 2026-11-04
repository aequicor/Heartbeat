package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed facts of one Pi connector build. These identify the source contract the adapter was compiled
 * from and the pinned Pi distribution; they are not a regression result or proof of the runtime classpath loaded
 * by the JVM. Generated files never belong to their own input set. No Git state, timestamps or absolute paths are
 * emitted.
 */
@CacheableTask
abstract class WritePiBuildContract : DefaultTask() {
    @get:Internal abstract val sourceRoot: DirectoryProperty

    /** Facade, authenticator, core and shared API sources defining the Heartbeat build this adapter embeds. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val heartbeatSources: ConfigurableFileCollection

    /** Pi connector and API sources plus the shared build inputs. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val connectorSources: ConfigurableFileCollection

    /** The pinned Pi distribution version from the version catalog, never a version read from a binary. */
    @get:Input abstract val runtimeVersion: Property<String>

    @get:Input abstract val packageName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val namespace = packageName.get()
        require(namespace.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        val runtime = runtimeVersion.get()
        require(runtime.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid Pi runtime version" }
        val directory = outputDirectory.get().asFile
        // This task owns the whole output directory; a package change must not leave stale source classes.
        check(!directory.exists() || directory.deleteRecursively()) { "Could not replace generated Pi contract" }
        val output = directory.resolve(namespace.replace('.', '/') + "/PiBuildContract.kt")
        output.parentFile.mkdirs()
        output.writeText(
            render(
                namespace = namespace,
                heartbeat = sourceHash(heartbeatSources.files),
                connector = sourceHash(connectorSources.files),
                runtime = runtime,
            ),
        )
    }

    private fun sourceHash(files: Set<File>): String {
        require(files.isNotEmpty()) { "Empty Pi build contract" }
        val root = sourceRoot.get().asFile.toPath().toRealPath()
        val entries = files.map { file ->
            val path = file.toPath().toRealPath()
            require(path.startsWith(root) && file.isFile) { "Source outside the declared build root" }
            root.relativize(path).toString().replace(File.separatorChar, '/') + "\u0000" + hash(file.readBytes())
        }.sorted()
        return hash(entries.joinToString("\n").toByteArray())
    }

    private fun render(namespace: String, heartbeat: String, connector: String, runtime: String): String =
        buildString {
            appendLine("package $namespace")
            appendLine()
            appendLine("/** Generated build inputs only; no compatibility or loaded-classpath admission is implied. */")
            appendLine("internal object PiBuildContract {")
            appendLine("    // Layout revision of these build facts, never a native configuration/history schema.")
            appendLine("    const val formatRevision: Int = 1")
            appendLine("    const val runtimeVersion: String = ${literal(runtime)}")
            appendLine("    const val heartbeat: String = ${literal(heartbeat)}")
            appendLine("    const val connector: String = ${literal(connector)}")
            appendLine("}")
        }

    private fun literal(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            append(
                when (char) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> char.toString()
                },
            )
        }
        append('"')
    }

    private fun hash(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
