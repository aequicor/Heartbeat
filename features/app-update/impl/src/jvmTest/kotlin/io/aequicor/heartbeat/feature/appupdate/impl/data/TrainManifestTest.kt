package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
