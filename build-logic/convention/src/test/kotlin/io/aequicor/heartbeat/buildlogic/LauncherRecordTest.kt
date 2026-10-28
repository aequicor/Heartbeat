package io.aequicor.heartbeat.buildlogic

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherRecordTest {
    private val sha = "0123456789abcdef".repeat(4)

    /** The same vector as `SlotRecordTest.canonicalBytesAreFixed` of the launcher selector: the signed bytes are a contract. */
    @Test
    fun `canonical record is the contract with the selector`() {
        val expected = "heartbeat-launcher-record-v1\n" +
            "id=launcher\n" +
            "version=t1a2b3c4d5e6f\n" +
            "sha256=$sha\n" +
            "kind=launcher\n" +
            "launcherApi=7\n"
        assertContentEquals(
            expected.toByteArray(Charsets.UTF_8),
            launcherRecordCanonical("launcher", "t1a2b3c4d5e6f", sha, "launcher", 7),
        )
    }

    @Test
    fun `signature is 88 characters of Base64 and verifies with the public key`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = launcherRecordCanonical("launcher", "t1", sha, "launcher", 3)
        val signature = signEd25519(Base64.getEncoder().encodeToString(keys.private.encoded), message)
        assertEquals(88, signature.length)
        assertTrue(Regex("[A-Za-z0-9+/]{86}==").matches(signature))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keys.public)
        verifier.update(message)
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)))
    }

    @Test
    fun `a changed field changes the signature`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val key = Base64.getEncoder().encodeToString(keys.private.encoded)
        val one = signEd25519(key, launcherRecordCanonical("launcher", "t1", sha, "launcher", 3))
        val other = signEd25519(key, launcherRecordCanonical("launcher", "t1", sha, "launcher", 4))
        assertTrue(one != other)
        // Ed25519 is deterministic: re-signing the same record gives the same bytes, so unchanged entries stay unchanged
        assertEquals(one, signEd25519(key, launcherRecordCanonical("launcher", "t1", sha, "launcher", 3)))
    }
}
