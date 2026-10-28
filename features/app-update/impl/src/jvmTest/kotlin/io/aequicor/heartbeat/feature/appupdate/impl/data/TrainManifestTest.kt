package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainManifestTest {
    private val sha = "ab".repeat(32)

    @Test
    fun `a valid manifest is parsed and its digests are lowercased`() {
        val manifest = parseTrainManifest(
            manifestJson("t-1", listOf("core.jar" to sha.uppercase())).replace("\"dbSchema\": 3,", "\"extra\": 1,"),
        )

        assertEquals("t-1", manifest.train)
        assertEquals(1, manifest.platformApi)
        assertNull(manifest.dbSchema)
        assertEquals(listOf(TrainBundle("core", "1.0", "core.jar", sha)), manifest.bundles)
    }

    @Test
    fun `the optional bundle url is kept and unknown fields are ignored`() {
        val withUrl = manifestJson("t-1", listOf("core.jar" to sha)).replace(
            "\"jar\": \"core.jar\",",
            "\"jar\": \"core.jar\", \"url\": \"https://example.com/core.jar\", \"future\": {\"a\": [1]},",
        )

        assertEquals(
            listOf(TrainBundle("core", "1.0", "core.jar", sha, "https://example.com/core.jar")),
            parseTrainManifest(withUrl).bundles,
        )
    }

    @Test
    fun `an optional dbSchema is kept`() {
        assertEquals(3, parseTrainManifest(manifestJson("t-1", listOf("core.jar" to sha))).dbSchema)
    }

    @Test
    fun `a manifest the launcher would refuse is an invalid release`() {
        val ok = manifestJson("t-1", listOf("core.jar" to sha, "ui.jar" to sha.reversed()))
        val broken = listOf(
            "not json",
            "{}",
            ok.replace("\"t-1\"", "\"../t\""),
            ok.replace("\"platformApi\": 1", "\"platformApi\": 0"),
            ok.replace("\"bundles\": [", "\"bundles\": [] , \"x\": ["),
            ok.replace("\"ui.jar\"", "\"sub/ui.jar\""),
            ok.replace("\"ui.jar\"", "\"..\\\\ui.jar\""),
            ok.replace("\"ui.jar\"", "\"ui.zip\""),
            ok.replace("\"ui.jar\"", "\"core.jar\""),
            ok.replace("\"id\": \"ui\"", "\"id\": \"core\""),
            ok.replace(sha, "abc"),
            ok.replace(sha.reversed(), "zz".repeat(32)),
        )
        broken.forEach { text ->
            assertUpdateFailure(AppUpdateFailure.InvalidRelease) { parseTrainManifest(text) }
        }
    }

    @Test
    fun `the launcher entry keeps its kind, level and signature`() {
        val signature = "A".repeat(86) + "=="
        val text = manifestJson("t-1", listOf("core.jar" to sha, "launcher.jar" to sha.reversed())).replace(
            "\"jar\": \"launcher.jar\",",
            "\"jar\": \"launcher.jar\", \"kind\": \"launcher\", \"launcherApi\": 4, \"signature\": \"$signature\",",
        )

        val bundles = parseTrainManifest(text).bundles
        val launcher = bundles.single { it.id == "launcher" }

        assertEquals("launcher", launcher.kind)
        assertEquals(4, launcher.launcherApi)
        assertEquals(signature, launcher.signature)
        assertNull(bundles.single { it.id == "core" }.kind)
    }

    @Test
    fun `the known kinds and an unsigned launcher entry are accepted`() {
        fun manifest(fields: String) = manifestJson("t-1", listOf("core.jar" to sha, "ui.jar" to sha.reversed()))
            .replace("\"jar\": \"ui.jar\",", fields)

        listOf("bundle", "base").forEach { kind ->
            val parsed = parseTrainManifest(manifest("\"jar\": \"ui.jar\", \"kind\": \"$kind\","))
            assertEquals(kind, parsed.bundles[1].kind)
        }
        val unsigned = parseTrainManifest(
            manifest("\"jar\": \"ui.jar\", \"kind\": \"launcher\", \"launcherApi\": 2,"),
        ).bundles[1]
        assertEquals("launcher", unsigned.kind)
        assertNull(unsigned.signature)
    }

    @Test
    fun `a train at exactly the installed platform level is parsed`() {
        val text = manifestJson("t-1", listOf("core.jar" to sha), platformApi = INSTALLED_PLATFORM_API)

        assertEquals(INSTALLED_PLATFORM_API, parseTrainManifest(text).platformApi)
    }

    @Test
    fun `a launcher entry the launcher would refuse is an invalid release`() {
        val signature = "A".repeat(86) + "=="
        val entry = "\"jar\": \"launcher.jar\", \"kind\": \"launcher\", \"launcherApi\": 4, " +
            "\"signature\": \"$signature\","
        val bundles = listOf("core.jar" to sha, "launcher.jar" to sha.reversed())
        fun manifest(fields: String) = manifestJson("t-1", bundles).replace("\"jar\": \"launcher.jar\",", fields)
        val broken = listOf(
            manifest(entry.replace("\"launcher\", \"launcherApi\"", "\"plugin\", \"launcherApi\"")),
            manifest(entry.replace("\"launcherApi\": 4, ", "")),
            manifest(entry.replace("\"launcherApi\": 4", "\"launcherApi\": 0")),
            manifest(entry.replace(signature, "short")),
            manifest(entry).replace(
                "\"jar\": \"core.jar\",",
                "\"jar\": \"core.jar\", \"kind\": \"launcher\", \"launcherApi\": 1,",
            ),
        )
        broken.forEach { text ->
            assertUpdateFailure(AppUpdateFailure.InvalidRelease) { parseTrainManifest(text) }
        }
    }

    @Test
    fun `a bundle url is trusted only as a release download of the configured repository`() {
        val repository = GitHubRepository("aequicor", "Heartbeat")

        assertTrue(isRepositoryDownload("$TRAIN_DOWNLOADS/v1.0.0/core.jar", repository))
        val otherCase = "https://github.com/Aequicor/heartbeat/releases/download/t-1/core.jar"
        assertTrue(isRepositoryDownload(otherCase, repository))
        listOf(
            "https://github.com/someone/Heartbeat/releases/download/v1.0.0/core.jar",
            "https://github.com/aequicor/HeartbeatX/releases/download/v1.0.0/core.jar",
            "http://github.com/aequicor/Heartbeat/releases/download/v1.0.0/core.jar",
            "$TRAIN_DOWNLOADS/../../../someone/Heartbeat/releases/download/v1/core.jar",
            "$TRAIN_DOWNLOADS/v1.0.0/../core.jar",
            "$TRAIN_DOWNLOADS/v1.0.0/core.jar?x=1",
            "$TRAIN_DOWNLOADS/v1.0.0/%2e%2e/core.jar",
            "$TRAIN_DOWNLOADS/core.jar",
        ).forEach { url -> assertFalse(isRepositoryDownload(url, repository), url) }
    }
}
