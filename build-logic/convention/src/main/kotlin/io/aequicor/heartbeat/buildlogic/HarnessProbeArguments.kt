package io.aequicor.heartbeat.buildlogic

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

/** Supplies the original pinned compiler files to the existing packaged probe, outside the installer payload. */
abstract class HarnessProbeArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val compilerFiles: ConfigurableFileCollection

    @get:Input abstract val fileNames: ListProperty<String>

    /** The feature's bootstrap checks each file's name, size and SHA again before creating the worker classloader. */
    override fun asArguments(): Iterable<String> = listOf("--heartbeat-harness-probe") + fileNames.get().map { name ->
        compilerFiles.files.single { it.name == name }.absolutePath
    }
}
