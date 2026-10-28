package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Packages of the project: only the launcher selector may be on the installer's class path. The launcher is loaded
 * from a slot jar in a class loader of its own, so its classes on the class path would not be used and would only confuse.
 */
private const val PROJECT_PREFIX = "io/aequicor/heartbeat/"
private const val SELECTOR_PREFIX = "io/aequicor/heartbeat/selector/"

/**
 * The class path of the installer is the parent of the train's class loader and wins over it: a project class there
 * would shadow the train, and an update would silently keep running the old code.
 */
internal fun projectClassesIn(entryNames: Sequence<String>): List<String> = entryNames
    .filter { it.endsWith(".class") && it.startsWith(PROJECT_PREFIX) && !it.startsWith(SELECTOR_PREFIX) }
    .take(MAX_REPORTED)
    .toList()

private fun checkNoProjectCode(name: String, jar: File) {
    val found = java.util.zip.ZipFile(jar).use { zip -> projectClassesIn(zip.entries().asSequence().map { it.name }) }
    check(found.isEmpty()) { "Platform jar $name carries project classes that would shadow the train: $found" }
}

private const val MAX_REPORTED = 5

/** Copies the platform jars of the installer into one directory under the unique [names] ([uniqueJarNames]). */
abstract class CollectPlatformJars : DefaultTask() {
    /** Installed file name -> jar. */
    @get:Input
    abstract val names: MapProperty<String, String>

    /** The jars the names point at. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Sets [installed] as the jars to collect. */
    fun collect(installed: org.gradle.api.provider.Provider<Map<String, File>>) {
        names.putAll(installed.map { map -> map.mapValues { it.value.absolutePath } })
        jars.from(installed.map { it.values })
    }

    @TaskAction
    fun copy() {
        names.get().forEach { (name, path) -> checkNoProjectCode(name, File(path)) }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        names.get().forEach { (name, path) ->
            Files.copy(File(path).toPath(), output.resolve(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
