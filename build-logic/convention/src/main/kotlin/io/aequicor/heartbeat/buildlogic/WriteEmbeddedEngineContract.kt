package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
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
 * Content-addressed facts of one controlled embedded JVM build. These identify source/build inputs and actual
 * resolved external artifacts, not a regression result or proof of the runtime classpath loaded by the JVM.
 * Generated files never belong to their own input set. No Git state, timestamps or absolute paths are emitted.
 */
@CacheableTask
abstract class WriteEmbeddedEngineContract : DefaultTask() {
    @get:Internal abstract val sourceRoot: DirectoryProperty

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val facadeSources: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val connectorSources: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val sdkArtifacts: ConfigurableFileCollection

    /** Artifact file name to the actual resolved module coordinate; duplicate file names must be rejected. */
    @get:Input abstract val coordinates: MapProperty<String, String>

    /** Artifact file name to the selected Gradle variant; this is part of the SDK identity. */
    @get:Input abstract val variants: MapProperty<String, String>

    @get:Input abstract val runtimeGroup: Property<String>
    @get:Input abstract val runtimeVersion: Property<String>
    @get:Input abstract val packageName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val namespace = packageName.get()
        require(namespace.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        val files = sdkArtifacts.files.toList()
        require(files.isNotEmpty()) { "Embedded build has no resolved artifacts" }
        require(files.map { it.name }.distinct().size == files.size) { "Ambiguous SDK artifact file names" }
        val moduleIds = coordinates.get()
        val selectedVariants = variants.get()
        require(moduleIds.keys == files.map { it.name }.toSet() && selectedVariants.keys == moduleIds.keys) {
            "Incomplete resolved artifact identity"
        }
        val sdkModules = moduleIds.values.filter { it.startsWith(runtimeGroup.get() + ":") }
        require(sdkModules.isNotEmpty() && sdkModules.all { it.substringAfterLast(':') == runtimeVersion.get() }) {
            "Resolved SDK differs from the pinned runtime version"
        }
        val entries = files.map { file ->
            Artifact(
                moduleIds.getValue(file.name), selectedVariants.getValue(file.name), file.name, hash(file.readBytes()),
            )
        }.sortedBy { it.key }
        val sdkHash = hash(entries.joinToString("\n") { it.key + "\u0000" + it.sha256 }.toByteArray())
        val host = sourceHash(facadeSources.files)
        val connector = sourceHash(connectorSources.files)
        // This task owns the whole output directory; a package change must not leave stale source classes.
        val directory = outputDirectory.get().asFile
        check(!directory.exists() || directory.deleteRecursively()) { "Could not replace generated embedded contract" }
        val output = directory.resolve(namespace.replace('.', '/') + "/EmbeddedEngineBuild.kt")
        output.parentFile.mkdirs()
        output.writeText(render(namespace, host, connector, sdkHash, entries))
    }

    private fun sourceHash(files: Set<File>): String {
        require(files.isNotEmpty()) { "Empty embedded source contract" }
        val root = sourceRoot.get().asFile.toPath().toRealPath()
        val entries = files.map { file ->
            val path = file.toPath().toRealPath()
            require(path.startsWith(root) && file.isFile) { "Source outside the declared build root" }
            root.relativize(path).toString().replace(File.separatorChar, '/') + "\u0000" + hash(file.readBytes())
        }.sorted()
        return hash(entries.joinToString("\n").toByteArray())
    }

    private fun render(
        namespace: String,
        host: String,
        connector: String,
        sdk: String,
        artifacts: List<Artifact>,
    ): String =
        buildString {
            appendLine("package $namespace")
            appendLine()
            appendLine("/** Generated build inputs only; no compatibility or loaded-classpath admission is implied. */")
            appendLine("internal object EmbeddedEngineBuild {")
            appendLine("    // Layout revision of these build facts, never a native configuration/history schema.")
            appendLine("    const val formatRevision: Int = 1")
            appendLine("    const val compiledFacade: String = ${literal(host)}")
            appendLine("    const val connector: String = ${literal(connector)}")
            appendLine("    const val runtimeVersion: String = ${literal(runtimeVersion.get())}")
            appendLine("    const val dependencies: String = ${literal(sdk)}")
            appendLine("    data class Artifact(")
            appendLine("        val coordinate: String, val variant: String, val file: String, val sha256: String,")
            appendLine("    )")
            appendLine("    val artifacts: List<Artifact> = listOf(")
            artifacts.forEach {
                val arguments = listOf(it.coordinate, it.variant, it.file, it.sha256).joinToString(", ", transform = ::literal)
                appendLine("        Artifact($arguments),")
            }
            appendLine("    )")
            appendLine("}")
        }

    private fun literal(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            append(when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '$' -> "\\$"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> char.toString()
            })
        }
        append('"')
    }

    private fun hash(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Artifact(val coordinate: String, val variant: String, val file: String, val sha256: String) {
        val key: String get() = "$coordinate\u0000$variant\u0000$file"
    }
}
