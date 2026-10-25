package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallProgressSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class TrainUpdateTest {
    private val root = createTempDirectory("app-update-train")
    private val repository = GitHubRepository("aequicor", "Heartbeat")
    private val list = "https://api.github.com/repos/aequicor/Heartbeat/releases?per_page=30"
    private val manifestUrl = "$TRAIN_DOWNLOADS/train-t2/train-manifest.json"
    private val core = "core-bytes".toByteArray()
    private val ui = "ui-bytes".toByteArray()
    private val requestedFiles = mutableListOf<String>()
    private val phases = mutableListOf<String>()
    private val offer = AppRelease("t2", "train-manifest.json", manifestUrl, "", null, "", train = "t2")

    // The older train t1 carries core.jar; t2 only ui.jar.
    private val manifest = manifestJson("t2", listOf("core.jar" to sha256(core), "ui.jar" to sha256(ui)))
    private val publications = listOf(
        releaseJson("train-t2", listOf("train-manifest.json" to 10L, "ui.jar" to ui.size.toLong())),
        releaseJson("train-t1", listOf("train-manifest.json" to 10L, "core.jar" to core.size.toLong())),
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `new jars are verified into the store and the manifest is queued last`() = runTest {
        update().prepare(repository, root, offer, progress())

        assertEquals(listOf("$TRAIN_DOWNLOADS/train-t1/core.jar", "$TRAIN_DOWNLOADS/train-t2/ui.jar"), requestedFiles)
        assertEquals("core-bytes", storeJar(root, sha256(core)).readText())
        assertEquals("ui-bytes", storeJar(root, sha256(ui)).readText())
        assertEquals(manifest, root.resolve("pending/t2/manifest.json").readText())
        assertFalse(root.resolve("staging/t2").exists())
        assertEquals("preparing", phases.last())
    }

    @Test
    fun `a jar that is already in the store is not downloaded again`() = runTest {
        Files.createDirectories(root.resolve("store"))
        Files.write(storeJar(root, sha256(core)), core)

        update().prepare(repository, root, offer, progress())

        assertEquals(listOf("$TRAIN_DOWNLOADS/train-t2/ui.jar"), requestedFiles)
        assertTrue(root.resolve("pending/t2/manifest.json").exists())
    }

    @Test
    fun `a jar with the wrong hash is corrupted and the train is not queued`() = runTest {
        val tampered = manifestJson("t2", listOf("core.jar" to sha256(core), "ui.jar" to sha256("other".toByteArray())))

        assertUpdateFailure(AppUpdateFailure.Corrupted) {
            update(manifestText = tampered).prepare(repository, root, offer, progress())
        }
        assertFalse(root.resolve("pending").exists())
        assertFalse(storeJar(root, sha256("other".toByteArray())).exists())
        // The failed download leaves no staging files behind.
        assertFalse(root.resolve("staging/t2").exists())
    }

    @Test
    fun `a jar no release carries makes the train invalid`() = runTest {
        assertUpdateFailure(AppUpdateFailure.InvalidRelease) {
            update(publications = publications.take(1)).prepare(repository, root, offer, progress())
        }
        assertFalse(root.resolve("pending").exists())
    }

    @Test
    fun `a train that needs a newer platform is refused`() = runTest {
        val newer = manifestJson("t2", listOf("ui.jar" to sha256(ui)), platformApi = 2)

        assertUpdateFailure(AppUpdateFailure.InvalidRelease) {
            update(manifestText = newer).prepare(repository, root, offer, progress())
        }
        assertEquals(emptyList<String>(), requestedFiles)
    }

    private fun progress() = object : InstallProgressSink {
        override suspend fun downloading(received: Long, total: Long?) {
            phases += "downloading $received/$total"
        }

        override suspend fun preparing() {
            phases += "preparing"
        }
    }

    private fun update(manifestText: String = manifest, publications: List<String> = this.publications): TrainUpdate {
        val contents = mapOf(
            "$TRAIN_DOWNLOADS/train-t2/ui.jar" to ui,
            "$TRAIN_DOWNLOADS/train-t1/core.jar" to core,
        )
        val files = object : VerifiedReleaseDownloader {
            override suspend fun download(
                request: ReleaseDownload,
                target: Path,
                progress: suspend (Long, Long?) -> Unit,
            ) {
                requestedFiles += request.url
                val bytes = contents.getValue(request.url)
                if (sha256(bytes) != request.sha256) throw ReleaseException(ReleaseFailure.ChecksumMismatch)
                Files.write(target, bytes)
                progress(bytes.size.toLong(), request.size)
            }
        }
        val source = object : ReleaseSource {
            override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease =
                throw ReleaseException(ReleaseFailure.Network)

            override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String = when (url) {
                list -> publications.joinToString(",", "[", "]")
                manifestUrl -> manifestText
                else -> throw ReleaseException(ReleaseFailure.Http, 404)
            }
        }
        return TrainUpdate(GitHubReleaseClient(source), UpdateDownloader(files, TestDispatchers), Dispatchers.IO)
    }
}
