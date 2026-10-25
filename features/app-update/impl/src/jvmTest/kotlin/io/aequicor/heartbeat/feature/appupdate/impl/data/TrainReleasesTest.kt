package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import io.aequicor.heartbeat.feature.appupdate.impl.domain.UpdateChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainReleasesTest {
    private val sha = "ab".repeat(32)
    private val list = "https://api.github.com/repos/aequicor/Heartbeat/releases?per_page=30"
    private val requested = mutableListOf<String>()

    private fun installation(
        channel: UpdateChannel,
        train: String? = "t1",
        bad: Set<String> = emptySet(),
        pending: Set<String> = emptySet(),
    ) = AppInstallation(
        AppVersion(1, 3, 0),
        GitHubRepository("aequicor", "Heartbeat"),
        ReleasePlatform.MacOsArm64,
        train,
        channel,
        badTrains = bad,
        pendingTrains = pending,
    )

    /** The release JSON and the `url to manifest` document of [train], published under [tag]. */
    private fun trainRelease(
        train: String,
        platformApi: Int = 1,
        tag: String = "train-$train",
        publishedAt: String? = null,
    ): Pair<String, Pair<String, String>> {
        val release = releaseJson(
            tag,
            listOf("train-manifest.json" to 10L, "core-$train.jar" to 100L),
            publishedAt = publishedAt,
        )
        val manifest = manifestJson(train, listOf("core-$train.jar" to sha), platformApi)
        return release to ("$TRAIN_DOWNLOADS/$tag/train-manifest.json" to manifest)
    }

    /** Releases of the trains [ids], newest first, and all their manifests. */
    private fun trainReleases(vararg ids: String): AppReleases {
        val published = ids.map { trainRelease(it) }
        return releases(published.map { it.first }, published.associate { it.second })
    }

    private fun releases(published: List<String>, documents: Map<String, String> = emptyMap()): AppReleases {
        val source = object : ReleaseSource {
            override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease {
                requested += "latest"
                throw ReleaseException(ReleaseFailure.Http, 404)
            }

            override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String {
                requested += url
                return if (url == list) published.joinToString(",", "[", "]") else documents.getValue(url)
            }
        }
        return AppReleases(GitHubReleaseClient(source))
    }

    @Test
    fun `stable ignores a pre-release train and never lists releases`() = runTest {
        val (release, document) = trainRelease("t2")

        val found = releases(listOf(release), mapOf(document)).newer(installation(UpdateChannel.Stable))

        assertNull(found)
        assertEquals(listOf("latest"), requested)
    }

    @Test
    fun `beta offers the newest train above the installed one`() = runTest {
        val (newest, newestDocument) = trainRelease("t3")
        val (older, olderDocument) = trainRelease("t2")
        val (installed, installedDocument) = trainRelease("t1")

        val found = releases(listOf(newest, older, installed), mapOf(newestDocument, olderDocument, installedDocument))
            .newer(installation(UpdateChannel.Beta))

        assertNotNull(found)
        assertEquals("t3", found.train)
        assertEquals("t3", found.version)
        assertEquals("train-manifest.json", found.assetName)
        assertEquals(newestDocument.first, found.url)
        assertEquals("https://github.com/aequicor/Heartbeat/releases/tag/train-t3", found.pageUrl)
        // Ids come from the tags: only the offered train's manifest is read.
        assertEquals(listOf(newestDocument.first), requested.filter { it.endsWith("train-manifest.json") })
    }

    @Test
    fun `a train the launcher rolled back is never offered again`() = runTest {
        val found = trainReleases("t3", "t2", "t1").newer(installation(UpdateChannel.Beta, bad = setOf("t3")))

        assertEquals("t2", found?.train)
    }

    @Test
    fun `only bad trains above the installed one fall back to full releases`() = runTest {
        val found = trainReleases("t3", "t2", "t1").newer(installation(UpdateChannel.Beta, bad = setOf("t3", "t2")))

        assertNull(found)
        assertTrue("latest" in requested)
    }

    @Test
    fun `a train older than the installed one is never offered`() = runTest {
        // t3 is the newest but rolled back; t1 is older than the installed t2: nothing is a newer train.
        val found = trainReleases("t3", "t2", "t1").newer(installation(UpdateChannel.Beta, "t2", bad = setOf("t3")))

        assertNull(found)
    }

    @Test
    fun `an installed train missing from the recent releases is not compared`() = runTest {
        assertNull(trainReleases("t3", "t2").newer(installation(UpdateChannel.Beta, "t0")))
    }

    @Test
    fun `a train already queued for the launcher is not offered again`() = runTest {
        assertNull(trainReleases("t3", "t2", "t1").newer(installation(UpdateChannel.Beta, pending = setOf("t3"))))
        assertEquals(
            "t3",
            trainReleases("t3", "t2", "t1").newer(installation(UpdateChannel.Beta, pending = setOf("t2")))?.train,
        )
    }

    @Test
    fun `the train of a full release is read from its manifest`() = runTest {
        val (newest, newestDocument) = trainRelease("t3", tag = "v1.4.0")
        val (installed, installedDocument) = trainRelease("t1")

        val found = releases(listOf(newest, installed), mapOf(newestDocument, installedDocument))
            .newer(installation(UpdateChannel.Beta))

        assertEquals("t3", found?.train)
    }

    @Test
    fun `a broken train manifest falls back to full releases`() = runTest {
        val (newest, newestDocument) = trainRelease("t3")
        val (installed, installedDocument) = trainRelease("t1")

        val found = releases(listOf(newest, installed), mapOf(newestDocument.first to "{}", installedDocument))
            .newer(installation(UpdateChannel.Beta))

        assertNull(found)
        assertTrue("latest" in requested)
    }

    @Test
    fun `beta with the newest train installed falls back to full releases`() = runTest {
        val (release, document) = trainRelease("t1")

        val found = releases(listOf(release), mapOf(document)).newer(installation(UpdateChannel.Beta))

        assertNull(found)
        assertTrue("latest" in requested)
    }

    @Test
    fun `a train that needs a newer platform is not offered`() = runTest {
        val (release, document) = trainRelease("t2", platformApi = 2)
        val (installed, installedDocument) = trainRelease("t1")

        val found = releases(listOf(release, installed), mapOf(document, installedDocument))
            .newer(installation(UpdateChannel.Beta))

        assertNull(found)
    }

    @Test
    fun `a launch without a launcher is never offered a train`() = runTest {
        val (release, document) = trainRelease("t2")

        val found = releases(listOf(release), mapOf(document)).newer(installation(UpdateChannel.Beta, train = null))

        assertNull(found)
        assertEquals(listOf("latest"), requested)
    }

    @Test
    fun `trains are ordered by publication, not by the listed order`() = runTest {
        val (installed, installedDocument) = trainRelease("t1", publishedAt = "2026-10-01T10:00:00Z")
        val (newest, newestDocument) = trainRelease("t3", publishedAt = "2026-10-03T10:00:00Z")
        val (older, olderDocument) = trainRelease("t2", publishedAt = "2026-10-02T10:00:00Z")

        val found = releases(listOf(installed, older, newest), mapOf(installedDocument, olderDocument, newestDocument))
            .newer(installation(UpdateChannel.Beta))

        assertEquals("t3", found?.train)
    }

    @Test
    fun `an older train listed above the installed one is never offered`() = runTest {
        val (older, olderDocument) = trainRelease("t1", publishedAt = "2026-10-01T10:00:00Z")
        val (installed, installedDocument) = trainRelease("t2", publishedAt = "2026-10-02T10:00:00Z")

        val found = releases(listOf(older, installed), mapOf(olderDocument, installedDocument))
            .newer(installation(UpdateChannel.Beta, "t2"))

        assertNull(found)
    }

    @Test
    fun `an untagged release with a broken manifest is skipped`() = runTest {
        val (broken, brokenDocument) = trainRelease("t4", tag = "v1.5.0")
        val (newest, newestDocument) = trainRelease("t3")
        val (installed, installedDocument) = trainRelease("t1")

        val found = releases(
            listOf(broken, newest, installed),
            mapOf(brokenDocument.first to "{}", newestDocument, installedDocument),
        ).newer(installation(UpdateChannel.Beta))

        assertEquals("t3", found?.train)
        assertTrue("latest" !in requested)
    }

    @Test
    fun `a manifest naming another train than its tag falls back to full releases`() = runTest {
        val (newest, newestDocument) = trainRelease("t3")
        val (installed, installedDocument) = trainRelease("t1")
        val mismatch = manifestJson("t9", listOf("core-t3.jar" to sha))

        val found = releases(listOf(newest, installed), mapOf(newestDocument.first to mismatch, installedDocument))
            .newer(installation(UpdateChannel.Beta))

        assertNull(found)
        assertTrue("latest" in requested)
    }
}
