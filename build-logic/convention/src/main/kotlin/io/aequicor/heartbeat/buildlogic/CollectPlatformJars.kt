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
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        names.get().forEach { (name, path) ->
            Files.copy(File(path).toPath(), output.resolve(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
