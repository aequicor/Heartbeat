package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Подпись bootstrap-манифеста: roundtrip, подмена байтов, чужой ключ, обрезанная подпись. */
class BootstrapSignatureTest {
    private val payload = """{"schema":1,"version":"1.2.3"}""".encodeToByteArray()

    private fun keyPair() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(privateKey: java.security.PrivateKey, bytes: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(bytes)
        return signer.sign()
    }

    @Test
    fun validSignatureVerifies() {
        val pair = keyPair()
        val signature = sign(pair.private, payload)
        assertTrue(BootstrapSignature.verify(payload, signature, listOf(pair.public.encoded)))
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val pair = keyPair()
        val signature = sign(pair.private, payload)
        val tampered = payload.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFalse(BootstrapSignature.verify(tampered, signature, listOf(pair.public.encoded)))
    }

    @Test
    fun anotherKeyIsRejected() {
        val pair = keyPair()
        val other = keyPair()
        val signature = sign(other.private, payload)
        assertFalse(BootstrapSignature.verify(payload, signature, listOf(pair.public.encoded)))
    }

    @Test
    fun oneOfSeveralTrustedKeysMatches() {
        val pair = keyPair()
        val other = keyPair()
        val signature = sign(pair.private, payload)
        assertTrue(BootstrapSignature.verify(payload, signature, listOf(other.public.encoded, pair.public.encoded)))
    }

    @Test
    fun malformedSignatureIsRejected() {
        val pair = keyPair()
        assertFalse(BootstrapSignature.verify(payload, ByteArray(63), listOf(pair.public.encoded)))
        assertFalse(BootstrapSignature.verify(payload, ByteArray(64), listOf(pair.public.encoded)))
        assertFalse(BootstrapSignature.verify(payload, ByteArray(64), emptyList()))
    }

    @Test
    fun malformedKeyIsRejectedWithoutThrowing() {
        val pair = keyPair()
        val signature = sign(pair.private, payload)
        assertFalse(BootstrapSignature.verify(payload, signature, listOf(ByteArray(44))))
    }
}
