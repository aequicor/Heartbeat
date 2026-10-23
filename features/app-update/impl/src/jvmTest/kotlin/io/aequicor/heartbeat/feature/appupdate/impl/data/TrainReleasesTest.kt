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

    private fun installation(channel: UpdateChannel, train: String? = "t1") = AppInstallation(
        AppVersion(1, 3, 0),
        GitHubRepository("aequicor", "Heartbeat"),
        ReleasePlatform.MacOsArm64,
        train,
        channel,
    )

    /** The release JSON and the `url to manifest` document of [train]. */
    private fun trainRelease(train: String, platformApi: Int = 1): Pair<String, Pair<String, String>> {
        val release = releaseJson("train-$train", listOf("train-manifest.json" to 10L, "core-$train.jar" to 100L))
        val manifest = manifestJson(train, listOf("core-$train.jar" to sha), platformApi)
        return release to ("$TRAIN_DOWNLOADS/train-$train/train-manifest.json" to manifest)
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
    fun `beta offers the newest train when it is not the installed one`() = runTest {
        val (newest, newestDocument) = trainRelease("t3")
        val (older, olderDocument) = trainRelease("t2")

        val found = releases(listOf(newest, older), mapOf(newestDocument, olderDocument))
            .newer(installation(UpdateChannel.Beta))

        assertNotNull(found)
        assertEquals("t3", found.train)
        assertEquals("t3", found.version)
        assertEquals("train-manifest.json", found.assetName)
        assertEquals(newestDocument.first, found.url)
        assertEquals("https://github.com/aequicor/Heartbeat/releases/tag/train-t3", found.pageUrl)
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

        assertNull(releases(listOf(release), mapOf(document)).newer(installation(UpdateChannel.Beta)))
    }

    @Test
    fun `a launch without a launcher is never offered a train`() = runTest {
        val (release, document) = trainRelease("t2")

        val found = releases(listOf(release), mapOf(document)).newer(installation(UpdateChannel.Beta, train = null))

        assertNull(found)
        assertEquals(listOf("latest"), requested)
    }
}
