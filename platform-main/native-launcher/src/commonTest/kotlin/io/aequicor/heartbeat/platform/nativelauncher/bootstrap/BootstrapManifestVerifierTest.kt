package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Валидация bootstrap-манифеста: схема, лимиты, HTTPS, формат SHA-256, поля артефактов. */
class BootstrapManifestVerifierTest {
    private val valid = """
        {
          "schema": 1,
          "version": "1.2.3",
          "channel": "release",
          "os": "windows",
          "arch": "x64",
          "minNativeLauncherVersion": 1,
          "runtime": {
            "id": "jbr-17.0.14-windows-x64",
            "url": "https://example.test/runtime.zip",
            "size": 110000000,
            "sha256": "${"a".repeat(64)}"
          },
          "platform": {
            "url": "https://example.test/platform.zip",
            "size": 445000000,
            "sha256": "${"b".repeat(64)}"
          },
          "seed": {
            "trainId": "train-o0123456789ab",
            "url": "https://example.test/seed.zip",
            "size": 50000000,
            "sha256": "${"c".repeat(64)}"
          }
        }
    """.trimIndent()

    @Test
    fun validManifestParses() {
        val manifest = BootstrapManifestVerifier.parse(valid.encodeToByteArray())
        assertEquals(1, manifest.schema)
        assertEquals("1.2.3", manifest.version)
        assertEquals("jbr-17.0.14-windows-x64", manifest.runtime.id)
        assertEquals("train-o0123456789ab", manifest.seed.trainId)
        assertEquals(445000000, manifest.platform.size)
    }

    @Test
    fun unknownFieldsAreToleratedWithinTheSchema() {
        val manifest = BootstrapManifestVerifier.parse(
            valid.replace("\"schema\": 1", "\"future\": true, \"schema\": 1").encodeToByteArray(),
        )
        assertEquals(1, manifest.schema)
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapManifestVerifier.parse(valid.replace("\"schema\": 1", "\"schema\": 2").encodeToByteArray())
        }
    }

    @Test
    fun oversizedManifestIsRejected() {
        val huge = ByteArray(BootstrapManifestVerifier.MAX_MANIFEST_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { BootstrapManifestVerifier.parse(huge) }
    }

    @Test
    fun nonHttpsArtifactUrlIsRejected() {
        val insecure = valid.replace(
            "https://example.test/runtime.zip",
            "http://example.test/runtime.zip",
        )
        assertFailsWith<IllegalArgumentException> {
            BootstrapManifestVerifier.parse(insecure.encodeToByteArray())
        }
    }

    @Test
    fun nonPositiveSizeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapManifestVerifier.parse(valid.replace("\"size\": 445000000", "\"size\": 0").encodeToByteArray())
        }
    }

    @Test
    fun malformedSha256IsRejected() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapManifestVerifier.parse(
                valid.replace("\"sha256\": \"${"a".repeat(64)}\"", "\"sha256\": \"deadbeef\"").encodeToByteArray(),
            )
        }
    }

    @Test
    fun blankTrainIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapManifestVerifier.parse(
                valid.replace("\"trainId\": \"train-o0123456789ab\"", "\"trainId\": \"\"").encodeToByteArray(),
            )
        }
    }

    @Test
    fun sha256HexMatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
        assertTrue(sha256Hex(ByteArray(0)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun httpIsLimitedToExactLoopbackAuthority() {
        listOf("http://localhost/a", "http://127.0.0.1:8080/a", "http://[::1]:123/a").forEach {
            kotlin.test.assertTrue(isAllowedArtifactUrl(it), it)
        }
        listOf(
            "http://localhost.evil/a",
            "http://127.0.0.10/a",
            "http://localhost@evil/a",
            "http://127.0.0.1.evil/a",
            "http://localhost:65536/a",
            "http://localhost:bad/a",
        ).forEach { kotlin.test.assertFalse(isAllowedArtifactUrl(it), it) }
    }
}
