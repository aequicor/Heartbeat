package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallProgressSink
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Prepares a bundle train for the platform launcher; the app itself replaces nothing. Contract with the launcher
 * (`platform-main/launcher`), all below its root directory:
 * - `store/<sha256-lowercase>.jar`: every jar the launcher may run, content-addressed. A jar that is already there is
 *   not downloaded again; a new one is downloaded to `staging/<train>/`, verified against the SHA-256 of the manifest
 *   and only then moved into `store/`.
 * - `pending/<train>/manifest.json`: the manifest as published, written last and atomically. Its presence means "all
 *   jars of this train are in `store/`"; the launcher activates the train at its next start (the user quits the app
 *   the way a full installer asks for) and rolls back on its own when the train does not start.
 *
 * Files in `store/` are never deleted here; the launcher owns their lifetime.
 */
@OptIn(ExperimentalPathApi::class)
internal class TrainUpdate(
    private val client: GitHubReleaseClient,
    private val downloader: UpdateDownloader,
    private val io: CoroutineDispatcher,
) {
    private val log = Log.tag("TrainUpdate")

    /** Downloads what the train [release] of [repository] adds to the store of [root] and queues the train. */
    suspend fun prepare(repository: GitHubRepository, root: Path, release: AppRelease, progress: InstallProgressSink) {
        val text = client.document(release.url)
        val manifest = parseTrainManifest(text)
        if (manifest.train != release.train) {
            updateFailure(AppUpdateFailure.InvalidRelease, "manifest train differs from the offered train")
        }
        if (manifest.platformApi > INSTALLED_PLATFORM_API) {
            updateFailure(AppUpdateFailure.InvalidRelease, "train ${manifest.train} needs a newer platform")
        }
        // The train's own release first: a jar it carries is the one that belongs to it.
        val releases = client.recent(repository).sortedByDescending { it.trainManifestAsset()?.url == release.url }
        val missing = withContext(io) {
            storage { manifest.missingDownloads(releases) { Files.exists(storeJar(root, it)) } }
        }
        log.i { "train download train=${manifest.train} bundles=${manifest.bundles.size} missing=${missing.size}" }
        val staging = root.resolve(STAGING).resolve(manifest.train)
        withContext(io) {
            storage {
                staging.deleteRecursively()
                Files.createDirectories(staging)
                Files.createDirectories(root.resolve(STORE))
            }
        }
        val total = missing.sumOf { it.asset.size }.takeIf { size -> size > 0 && missing.all { it.asset.size > 0 } }
        var done = 0L
        for (download in missing) {
            val staged = staging.resolve(download.bundle.jar)
            val before = done
            downloader.downloadFile(
                download.asset.url,
                download.bundle.sha256,
                download.asset.size,
                staged,
            ) { received, _ -> progress.downloading(before + received, total) }
            done += download.asset.size
            withContext(io) { storage { moveInto(staged, storeJar(root, download.bundle.sha256)) } }
        }
        progress.preparing()
        withContext(io) {
            storage {
                queue(root, manifest.train, text)
                staging.deleteRecursively()
            }
        }
        log.i { "train queued for the launcher train=${manifest.train}" }
    }

    private fun queue(root: Path, train: String, manifest: String) {
        val directory = Files.createDirectories(root.resolve(PENDING).resolve(train))
        val temporary = directory.resolve("$MANIFEST.tmp")
        Files.writeString(temporary, manifest)
        moveInto(temporary, directory.resolve(MANIFEST))
    }

    private fun moveInto(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw AppUpdateException(AppUpdateFailure.Storage, "train files could not be written", e.withoutDetails())
    }

    private companion object {
        const val STORE = "store"
        const val STAGING = "staging"
        const val PENDING = "pending"
        const val MANIFEST = "manifest.json"
    }
}

/** The store file of the jar with the lowercase hex [sha256] below the launcher [root]. */
internal fun storeJar(root: Path, sha256: String): Path = root.resolve("store").resolve("$sha256.jar")
