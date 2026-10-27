package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The seed of an installer (`FirstLaunch` of the platform launcher): the `manifest.json` of a whole train and the jars
 * it lists, so a fresh installation starts offline and activates its first train from them. The manifest is the output of
 * `bundleManifest` with the train id the launcher requires.
 */
abstract class WriteTrainSeed : DefaultTask() {
    /** Output of the root `bundleManifest` task. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** Plain train id (a directory name of the launcher). */
    @get:Input
    abstract val trainId: Property<String>

    /** Candidate jars; only the ones the manifest names are copied, by file name. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val seedDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val id = trainId.get()
        if (!TRAIN_ID.matches(id)) throw GradleException("Train id $id is not a plain name")
        val text = manifest.get().asFile.readText()
        val listed = JAR_ENTRY.findAll(text).map { it.groupValues[1] }.toList()
        if (listed.isEmpty()) throw GradleException("The manifest lists no jar")
        val byName = jars.files.associateBy { it.name }
        val output = seedDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        listed.forEach { name ->
            val jar = byName[name] ?: throw GradleException("Jar $name of the manifest is missing")
            jar.copyTo(output.resolve(name))
        }
        output.resolve(MANIFEST).writeText(withTrainId(text, id))
    }

    internal companion object {
        const val MANIFEST = "manifest.json"
        private val TRAIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val JAR_ENTRY = Regex("\"jar\":\\s*\"([^\"]+)\"")

        /** Puts the `train` field first in the object [manifest] renders (see [renderBundleManifest]). */
        internal fun withTrainId(manifest: String, id: String): String {
            val open = manifest.indexOf('{')
            if (open < 0) throw GradleException("The manifest is not a JSON object")
            return manifest.substring(0, open + 1) + "\n  \"train\": \"$id\"," + manifest.substring(open + 1)
        }
    }
}
