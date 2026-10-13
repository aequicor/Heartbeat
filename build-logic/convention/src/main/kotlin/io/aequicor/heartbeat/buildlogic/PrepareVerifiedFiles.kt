package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Fetches pinned third-party files that are not Maven artifacts (the side panel's fallback fonts) into
 * [outputDirectory]/[subdirectory]. Every file is named by its key in [urls] and must match its SHA-256 in
 * [sha256].
 *
 * Downloads are cached in [cacheDirectory] (Gradle user home, outside `build/`) under their checksum, so clean
 * builds and `--offline` runs reuse them (see [VerifiedDownload]); the repository keeps no binary copies.
 */
@DisableCachingByDefault(because = "Copies pinned downloads; they are cached in Gradle user home")
abstract class PrepareVerifiedFiles : DefaultTask() {
    /** Output file name → download URL. */
    @get:Input abstract val urls: MapProperty<String, String>

    /** Output file name → expected SHA-256 (lowercase hex). */
    @get:Input abstract val sha256: MapProperty<String, String>

    /** Directory inside [outputDirectory] that receives the files, e.g. `files/fonts`. */
    @get:Input abstract val subdirectory: Property<String>

    /** Mirrors `--offline`: never download, only reuse verified cached files. */
    @get:Internal abstract val offline: Property<Boolean>

    /** Persistent download cache, e.g. `<gradleUserHome>/caches/heartbeat/fonts`. */
    @get:Internal abstract val cacheDirectory: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun prepare() {
        val sources = urls.get()
        val checksums = sha256.get()
        check(sources.keys == checksums.keys) { "Every pinned file needs exactly one URL and one SHA-256" }
        val download = VerifiedDownload(logger, offline.get())
        val cached = sources.map { (name, url) ->
            val checksum = checksums.getValue(name)
            download.fetch(url, checksum, cacheDirectory.get().asFile.resolve("$checksum/$name"), name)
        }
        files.sync {
            from(cached)
            into(outputDirectory.dir(subdirectory))
        }
    }
}
