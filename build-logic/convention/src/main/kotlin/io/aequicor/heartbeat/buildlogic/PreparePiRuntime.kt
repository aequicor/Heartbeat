package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Fetches the pinned standalone Pi distribution and unpacks it into [outputDirectory] as `common/pi`
 * (the layout Compose Desktop expects from `appResourcesRootDir`).
 *
 * The release archive is cached in [cacheDirectory] (Gradle user home, outside `build/`) and reused while
 * its SHA-256 matches [sha256], so clean builds and `--offline` runs do not hit the network. A missing or
 * mismatching cache is re-downloaded from [baseUrl] into a temporary file and atomically moved into place;
 * in [offline] mode the task fails instead.
 *
 * Archive layouts differ per platform: `.tar.gz` assets wrap everything in a single top-level directory,
 * which is stripped; `.zip` (Windows) assets are flat and copied as is. An archive `LICENSE` is kept
 * unchanged; assets without one (the Windows zip) get [fallbackLicense] copied next to the executable.
 * The pinned [version] is written to `VERSION` next to the executable: the app reports it without starting Pi
 * and treats it as the verified version when the user installs a newer release.
 */
@DisableCachingByDefault(because = "Downloads and extracts a large binary; the archive is cached in Gradle user home")
abstract class PreparePiRuntime : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @get:Input abstract val asset: Property<String>

    @get:Input abstract val sha256: Property<String>

    /** Release download root; the final URL is `<baseUrl>/v<version>/<asset>`. */
    @get:Input abstract val baseUrl: Property<String>

    /** Mirrors `--offline`: never download, only reuse a verified cached archive. */
    @get:Internal abstract val offline: Property<Boolean>

    /** Persistent archive cache, e.g. `<gradleUserHome>/caches/heartbeat/pi`. */
    @get:Internal abstract val cacheDirectory: DirectoryProperty

    /**
     * Checked-in copy of upstream's `LICENSE`, used when the archive has none.
     * Update it whenever upstream changes its license text.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fallbackLicense: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun prepare() {
        val archive = cachedArchive()
        val zipped = asset.get().endsWith(".zip")
        val tree = if (zipped) archives.zipTree(archive) else archives.tarTree(archive)
        files.sync {
            from(tree)
            into(outputDirectory.dir("common/pi"))
            includeEmptyDirs = false
            if (!zipped) {
                eachFile {
                    relativePath = RelativePath(!isDirectory, *relativePath.segments.drop(1).toTypedArray())
                }
            }
        }
        val payload = outputDirectory.dir("common/pi").get().asFile
        val executable = payload.resolve(if (zipped) "pi.exe" else "pi")
        check(executable.isFile) { "Pi archive ${asset.get()} did not contain its executable" }
        check(zipped || executable.setExecutable(true, false)) { "Cannot mark bundled Pi executable" }
        payload.resolve("VERSION").writeText(version.get())
        val license = payload.resolve("LICENSE")
        if (!license.isFile) {
            logger.info("Pi archive ${asset.get()} has no LICENSE; bundling the checked-in upstream copy")
            fallbackLicense.get().asFile.copyTo(license)
        }
    }

    private fun cachedArchive(): File = VerifiedDownload(logger, offline.get()).fetch(
        url = "${baseUrl.get().trimEnd('/')}/v${version.get()}/${asset.get()}",
        sha256 = sha256.get(),
        target = cacheDirectory.dir(version.get()).get().asFile.resolve(asset.get()),
        label = "Pi ${version.get()} archive ${asset.get()}",
    )
}
